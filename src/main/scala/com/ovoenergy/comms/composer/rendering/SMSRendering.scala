package com.ovoenergy.comms.composer.rendering

import java.time.Clock

import cats.Id
import cats.implicits._
import com.ovoenergy.comms.composer.sms.RenderedSMS
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import scala.collection.JavaConverters._
object SMSRendering extends Rendering {

  def renderSMS(clock: Clock)(commManifest: CommManifest,
                              template: SMSTemplate[Id],
                              data: Map[String, TemplateData],
                              customerProfile: Option[CustomerProfile],
                              recipientPhoneNumber: String): Either[FailedToRender, RenderedSMS] = {

    val customerProfileMap: Map[String, AnyRef] = customerProfile
      .map(profile => Map("profile" -> valueToMap(profile).asJava))
      .getOrElse(Map.empty)

    val phoneNumberMap: Map[String, AnyRef] = Map("recipient" -> Map("phoneNumber" -> recipientPhoneNumber).asJava)

    val context = buildHandlebarsContext(
      data,
      phoneNumberMap.asJava.combineWith(customerProfileMap.asJava),
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
