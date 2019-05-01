package com.ovoenergy.comms.composer
package logic

import cats._
import cats.data.OptionT
import cats.implicits._

import org.http4s.Uri

import com.ovoenergy.comms.model.{MetadataV3, TemplateData, InvalidTemplate}
import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import com.ovoenergy.comms.templates.model.EmailSender

import rendering.TextRenderer
import model._

object Email {

  val defaultSender = EmailSender("OVO Energy", "no-reply@ovoenergy.com")

  def apply[F[_], G[_]](event: OrchestratedEmailV4)(
      implicit parallel: Parallel[F, G],
      ae: MonadError[F, Throwable],
      store: Store[F],
      textRenderer: TextRenderer[F],
      time: Time[F]): F[ComposedEmailV4] = {

    val commId: CommId = event.metadata.commId
    val traceToken: TraceToken = event.metadata.traceToken
    val templateManifest = event.metadata.templateManifest

    val recipientData = Map(
      "recipient" ->
        TemplateData.fromMap(
          Map("emailAddress" -> TemplateData.fromString(event.recipientEmailAddress))
        )
    )

    def renderEmail(data: TemplateData): F[RenderedEmail] = {

      def upload(f: F[Option[RenderedFragment]]): F[Option[Uri]] = {
        OptionT(f).semiflatMap { fragment =>
          store.upload(commId, traceToken, fragment)
        }.value
      }

      val renderSubject: F[Uri] =
        upload(
          textRenderer.render(
            templateFragmentIdFor(templateManifest, TemplateFragmentType.Email.Subject),
            data)).orRaiseError(
          new ComposerError(
            s"Template ${templateManifest.show} does not have the required email subject fragment",
            InvalidTemplate)
        )
      val renderHtmlBody: F[Uri] =
        upload(
          textRenderer.render(
            templateFragmentIdFor(templateManifest, TemplateFragmentType.Email.HtmlBody),
            data)).orRaiseError(
          new ComposerError(
            s"Template ${templateManifest.show} does not have the required email html body fragment",
            InvalidTemplate)
        )
      val renderTextBody: F[Option[Uri]] = upload(
        textRenderer.render(
          templateFragmentIdFor(templateManifest, TemplateFragmentType.Email.TextBody),
          data))

      val renderSender: F[EmailSender] =
        textRenderer
          .render(templateFragmentIdFor(templateManifest, TemplateFragmentType.Email.Sender), data)
          .flatMap { optFragment =>
            optFragment.fold(defaultSender.pure[F]) { renderedSender =>
              EmailSender
                .parse(renderedSender.value)
                .fold(
                  errors =>
                    new ComposerError(
                      s"Template ${templateManifest.show} fragment email sender is not valid",
                      InvalidTemplate).raiseError[F, EmailSender],
                  _.pure[F])
            }
          }

      (renderSender, renderSubject, renderHtmlBody, renderTextBody).parMapN {
        (sender, subject, htmlBody, textBody) =>
          RenderedEmail(
            sender,
            RenderedEmail.Subject(subject),
            RenderedEmail.HtmlBody(htmlBody),
            textBody.map(RenderedEmail.TextBody)
          )
      }
    }

    for {
      now <- time.now
      templateData = buildTemplateData(
        now,
        event.customerProfile,
        recipientData ++ event.templateData
      )
      rendered <- renderEmail(templateData)
    } yield
      ComposedEmailV4(
        metadata = MetadataV3.fromSourceMetadata(
          "comms-composer",
          event.metadata,
          event.metadata.commId ++ "-composed-email"
        ),
        internalMetadata = event.internalMetadata,
        sender = rendered.sender.toString,
        recipient = event.recipientEmailAddress,
        subject = rendered.subject.uri.renderString,
        htmlBody = rendered.htmlBody.uri.renderString,
        textBody = rendered.textBody.map(_.uri.renderString),
        expireAt = event.expireAt,
        hashedComm = "NA"
      )

  }
}
