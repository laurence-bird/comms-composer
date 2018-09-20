package com.ovoenergy.comms.composer.rendering.templating

import java.time.Clock

import cats.implicits._
import cats.{Apply, Id}
import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.composer.rendering.{ErrorsOr, FailedToRender}
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.TemplateManifest
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

object EmailTemplateRendering extends Rendering {

  def renderEmail(clock: Clock,
                  templateManifest: TemplateManifest,
                  template: EmailTemplate[Id],
                  emailTemplateData: CommTemplateData): Either[FailedToRender, RenderedEmail] = {
    val context = buildHandlebarsContext(
      emailTemplateData.buildHandlebarsData,
      clock
    )

    val subject: ErrorsOr[String] = {
      val filename = buildFilename(templateManifest, model.Email, "subject")
      HandlebarsWrapper.render(filename, template.subject)(context)
    }
    val htmlBody: ErrorsOr[String] = {
      val filename = buildFilename(templateManifest, model.Email, "htmlBody")
      HandlebarsWrapper.render(filename, template.htmlBody)(context)
    }
    val textBody: Option[ErrorsOr[String]] =
      template.textBody map { tb =>
        val filename = buildFilename(templateManifest, model.Email, "textBody")
        HandlebarsWrapper.render(filename, tb)(context)
      }

    val errorsOrResult: ErrorsOr[RenderedEmail] =
      Apply[ErrorsOr].map3(subject, htmlBody, textBody.sequence[ErrorsOr, String]) {
        case (s, h, t) => RenderedEmail(s, h, t)
      }

    errorsOrResult
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }
}
