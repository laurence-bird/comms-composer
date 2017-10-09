package com.ovoenergy.comms.composer.rendering.templating

import java.time.Clock

import cats.implicits._
import cats.kernel.Monoid
import cats.{Apply, Id}
import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.composer.rendering.{ErrorsOr, FailedToRender}
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

object EmailTemplateRendering extends Rendering {

  def renderEmail(clock: Clock)(commManifest: CommManifest,
                                template: EmailTemplate[Id],
                                data: Map[String, TemplateData],
                                customerProfile: Option[CustomerProfile],
                                recipientEmailAddress: String): Either[FailedToRender, RenderedEmail] = {

    val emailAddressMap: Map[String, Map[String, String]] = Map(
      "recipient" -> Map("emailAddress" -> recipientEmailAddress))

    val customerProfileMap: Map[String, Map[String, String]] = customerProfile
      .map { c =>
        Map("profile" -> valueToMap(c))
      }
      .getOrElse(Map.empty[String, Map[String, String]])

    val customerData: Map[String, Map[String, String]] =
      Monoid.combine(emailAddressMap, customerProfileMap)

    val context = buildHandlebarsContext(
      data,
      customerData,
      clock
    )

    val subject: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, model.Email, "subject")
      HandlebarsWrapper.render(filename, template.subject)(context)
    }
    val htmlBody: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, model.Email, "htmlBody")
      HandlebarsWrapper.render(filename, template.htmlBody)(context)
    }
    val textBody: Option[ErrorsOr[String]] =
      template.textBody map { tb =>
        val filename = buildFilename(commManifest, model.Email, "textBody")
        HandlebarsWrapper.render(filename, tb)(context)
      }

    val errorsOrResult: ErrorsOr[RenderedEmail] =
      Apply[ErrorsOr].map3(subject, htmlBody, textBody.sequenceU) {
        case (s, h, t) => RenderedEmail(s, h, t)
      }

    errorsOrResult
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }

}
