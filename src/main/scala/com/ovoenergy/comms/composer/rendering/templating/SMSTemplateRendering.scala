package com.ovoenergy.comms.composer.rendering.templating

import java.time.Clock

import cats.Id
import com.ovoenergy.comms.composer.rendering.{ErrorsOr, FailedToRender}
import com.ovoenergy.comms.composer.sms.RenderedSMS
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest}
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

object SMSTemplateRendering extends Rendering {

  def renderSMS(clock: Clock,
                commManifest: CommManifest,
                template: SMSTemplate[Id],
                smsTemplateData: CommTemplateData): Either[FailedToRender, RenderedSMS] = {

    val context = buildHandlebarsContext(
      smsTemplateData.buildHandlebarsData,
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
