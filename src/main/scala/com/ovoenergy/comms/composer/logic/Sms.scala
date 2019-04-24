package com.ovoenergy.comms.composer
package logic

import cats._
import cats.data.OptionT
import cats.implicits._

import org.http4s.Uri

import com.ovoenergy.comms.model.{MetadataV3, TemplateData, InvalidTemplate}
import com.ovoenergy.comms.model.sms.{OrchestratedSMSV3, ComposedSMSV4}
import com.ovoenergy.comms.templates.model.EmailSender

import rendering.TextRenderer
import model._

object Sms {

  val bodyTemplateFragmentId = TemplateFragmentId("body.txt")

  def apply[F[_]](event: OrchestratedSMSV3)(
      implicit ae: MonadError[F, Throwable],
      store: Store[F],
      textRenderer: TextRenderer[F],
      time: Time[F]): F[ComposedSMSV4] = {

    val commId: CommId = event.metadata.commId
    val traceToken: TraceToken = event.metadata.traceToken

    val recipientData = Map(
      "recipient" ->
        TemplateData.fromMap(
          Map("phoneNumber" -> TemplateData.fromString(event.recipientPhoneNumber))
        )
    )

    def renderSms(data: Map[String, TemplateData]): F[RenderedSms] = {

      def upload(f: F[Option[RenderedFragment]]): F[Option[Uri]] = {
        OptionT(f).semiflatMap { fragment =>
          store.upload(commId, traceToken, fragment)
        }.value
      }

      upload(textRenderer.render(bodyTemplateFragmentId, data))
        .orRaiseError(
          new ComposerError(
            s"Template does not have the required ${bodyTemplateFragmentId.value} fragment",
            InvalidTemplate)
        )
        .map { uri =>
          RenderedSms(RenderedSms.Sender(event.recipientPhoneNumber), RenderedSms.Body(uri))
        }
    }

    for {
      now <- time.now
      templateData = buildTemplateData(
        now,
        event.customerProfile,
        recipientData ++ event.templateData
      )
      rendered <- renderSms(templateData)
    } yield
      ComposedSMSV4(
        metadata = MetadataV3.fromSourceMetadata(
          "comms-composer",
          event.metadata,
          event.metadata.commId ++ "-composed-sms"
        ),
        internalMetadata = event.internalMetadata,
        recipient = rendered.sender.content,
        textBody = rendered.body.uri.renderString,
        expireAt = event.expireAt,
        hashedComm = "N/A"
      )

  }
}
