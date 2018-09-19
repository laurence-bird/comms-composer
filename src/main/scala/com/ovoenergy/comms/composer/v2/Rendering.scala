package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

import cats.Apply
import cats.data.Validated
import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.composer.rendering.Errors
import com.ovoenergy.comms.composer.rendering.templating.CommTemplateData
import com.ovoenergy.comms.composer.v2.Templates.{Email, Print, Sms}
import com.ovoenergy.comms.composer.v2.model._
import com.ovoenergy.comms.model.{Channel, TemplateManifest, Email => EmailChan, Print => PrintChan, SMS => SmsChan}
import com.ovoenergy.comms.templates.s3.S3Prefix // TODO fix me

trait Rendering[F[_]] {
  def renderEmail(time: ZonedDateTime,
                  manifest: TemplateManifest,
                  template: Templates.Email,
                  emailTemplateData: CommTemplateData): F[Email.Rendered]
  def renderSms(time: ZonedDateTime,
                manifest: TemplateManifest,
                template: Templates.Sms,
                smsTemplateData: CommTemplateData): F[SMS.Rendered]
  def renderPrintHtml(time: ZonedDateTime,
                      manifest: TemplateManifest,
                      template: Templates.Print,
                      printTemplateData: CommTemplateData): F[Print.HtmlBody]
  def renderPrintPdf(html: Print.HtmlBody): F[Print.RenderedPdf]
}

object Rendering {

  def fileName(channel: Channel, manifest: TemplateManifest, suffixes: String*) =
    (Seq(S3Prefix.fromTemplateManifest(manifest), channel.toString) ++ suffixes).mkString("::")

  def apply[F[_]](handlebars: HandlebarsRendering)(implicit F: Sync[F]) =
    new Rendering[F] {
      override def renderEmail(time: ZonedDateTime,
                               manifest: TemplateManifest,
                               template: Email,
                               emailTemplateData: CommTemplateData): F[Email.Rendered] = {
        val channel = EmailChan
        val subject =
          handlebars.render(template.subject, time, emailTemplateData, fileName(channel, manifest, "subject"))
        val htmlBody =
          handlebars.render(template.htmlBody, time, emailTemplateData, fileName(channel, manifest, "htmlBody"))
        val textBody =
          template.textBody.map(handlebars.render(_, time, emailTemplateData, fileName(channel, manifest, "textBody")))

        val errorsOrResult: Validated[Errors, Email.Rendered] =
          Apply[Validated[Errors, ?]].map3(subject, htmlBody, textBody.sequence[Validated[Errors, ?], String]) {
            case (s, h, t) => Email.Rendered(Email.Subject(s), Email.HtmlBody(h), t.map(Email.TextBody))
          }

        F.fromEither {
          errorsOrResult.toEither
            .leftMap(err => ComposerError(err.toErrorMessage, err.errorCode))
        }
      }
      override def renderSms(time: ZonedDateTime,
                             manifest: TemplateManifest,
                             template: Sms,
                             smsTemplateData: CommTemplateData): F[SMS.Rendered] = {

        val textBody =
          handlebars.render(template.textBody, time, smsTemplateData, fileName(SmsChan, manifest, "textBody"))

        F.fromEither {
          textBody
            .map(body => SMS.Rendered(SMS.Body(body)))
            .leftMap(errors => ComposerError(errors.toErrorMessage, errors.errorCode))
            .toEither
        }
      }

      override def renderPrintHtml(time: ZonedDateTime,
                                   manifest: TemplateManifest,
                                   template: Print,
                                   printTemplateData: CommTemplateData): F[Print.HtmlBody] = {
        val renderedPrintHtml = handlebars
          .render(
            template.body,
            time,
            printTemplateData,
            fileName(PrintChan, manifest, "htmlBody")
          )
          .map(renderedHtmlBody => Print.HtmlBody(renderedHtmlBody))

        F.fromEither {
          renderedPrintHtml
            .leftMap(errors => ComposerError(errors.toErrorMessage, errors.errorCode))
            .toEither
        }
      }

      override def renderPrintPdf(html: Print.HtmlBody): F[Print.RenderedPdf] = ???

    }
}
