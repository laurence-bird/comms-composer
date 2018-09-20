package com.ovoenergy.comms.composer
package v2
package rendering

import java.time.ZonedDateTime

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.composer.rendering.Errors
import com.ovoenergy.comms.composer.rendering.templating.CommTemplateData
import v2.Templates.{Email, Print, Sms}
import model._
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

        val subject: F[Validated[Errors, String]] =
          F.delay(handlebars.render(template.subject, time, emailTemplateData, fileName(channel, manifest, "subject")))

        val htmlBody: F[Validated[Errors, String]] =
          F.delay(
            handlebars.render(template.htmlBody, time, emailTemplateData, fileName(channel, manifest, "htmlBody")))

        val textBody: F[Option[Validated[Errors, String]]] =
          F.delay(
            template.textBody.map(
              handlebars.render(_, time, emailTemplateData, fileName(channel, manifest, "textBody"))))

        val errorsOrResult: F[Validated[Errors, Email.Rendered]] = for {
          a <- subject
          b <- htmlBody
          c <- textBody
        } yield {
          (a, b, c.sequence).mapN { (s, h, t) =>
            Email.Rendered(Email.Subject(s), Email.HtmlBody(h), t.map(Email.TextBody))
          }
        }
        errorsOrResult.flatMap {
          case Valid(r) => F.delay(r)
          case Invalid(err) => F.raiseError(ComposerError(err.toErrorMessage, err.errorCode))
        }
      }

      override def renderSms(time: ZonedDateTime,
                             manifest: TemplateManifest,
                             template: Sms,
                             smsTemplateData: CommTemplateData): F[SMS.Rendered] = {

        F.delay(handlebars.render(template.textBody, time, smsTemplateData, fileName(SmsChan, manifest, "textBody")))
          .flatMap {
            case Valid(body) => F.delay(SMS.Rendered(SMS.Body(body)))
            case Invalid(err) => F.raiseError(ComposerError(err.toErrorMessage, err.errorCode))
          }
      }

      override def renderPrintHtml(time: ZonedDateTime,
                                   manifest: TemplateManifest,
                                   template: Print,
                                   printTemplateData: CommTemplateData): F[Print.HtmlBody] = {
        F.delay(handlebars.render(template.body, time, printTemplateData, fileName(PrintChan, manifest, "htmlBody")))
          .flatMap {
            case Valid(body) => F.delay(Print.HtmlBody(body))
            case Invalid(errors) => F.raiseError(ComposerError(errors.toErrorMessage, errors.errorCode))
          }
      }

      override def renderPrintPdf(html: Print.HtmlBody): F[Print.RenderedPdf] = ???

    }
}
