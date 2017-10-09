package com.ovoenergy.comms.composer.rendering.templating

import java.time.Clock

import cats.Id
import cats.implicits._
import cats.kernel.Monoid
import com.ovoenergy.comms.composer.rendering.{ErrorsOr, FailedToRender}
import com.ovoenergy.comms.composer.sms.RenderedSMS
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

object SMSTemplateRendering extends Rendering {

  def renderSMS(clock: Clock)(commManifest: CommManifest,
                              template: SMSTemplate[Id],
                              data: Map[String, TemplateData],
                              customerProfile: Option[CustomerProfile],
                              recipientPhoneNumber: String): Either[FailedToRender, RenderedSMS] = {

    val customerProfileMap = customerProfile
      .map(profile => Map("profile" -> valueToMap(profile)))
      .getOrElse(Map.empty)

    val phoneNumberMap = Map("recipient" -> Map("phoneNumber" -> recipientPhoneNumber))

    val customerData = Monoid.combine(customerProfileMap, phoneNumberMap)

    val context = buildHandlebarsContext(
      data,
      customerData,
      clock
    )

    val textBody: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, model.SMS, "textBody")
      HandlebarsWrapper.render(filename, template.textBody)(context)
    }

    textBody
      .map(RenderedSMS)
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }
}
