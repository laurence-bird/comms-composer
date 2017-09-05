package com.ovoenergy.comms.composer.rendering

import java.time.Clock

import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest, CustomerAddress, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import cats.{Apply, Id}
import scala.collection.JavaConverters._
import cats.implicits._

object EmailRenderer extends Rendering{

  def renderEmail(clock: Clock)(commManifest: CommManifest,
                                template: EmailTemplate[Id],
                                data: Map[String, TemplateData],
                                customerProfile: Option[CustomerProfile],
                                recipientEmailAddress: String): Either[FailedToRender, RenderedEmail] = {

    val emailAddressMap: Map[String, AnyRef] = Map("recipient" -> Map("emailAddress" -> recipientEmailAddress).asJava)

    val customerProfileMap = customerProfile
      .map{c =>
        Map("profile" -> valueToMap(c).asJava)
      }
      .getOrElse(Map.empty[String, AnyRef])


    val context = buildHandlebarsContext(
      data,
      combineJMaps(customerProfileMap.asJava, emailAddressMap.asJava),
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
