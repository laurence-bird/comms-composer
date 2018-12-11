package com.ovoenergy.comms.composer
package logic

import cats.FlatMap
import cats.implicits._
import com.ovoenergy.comms.composer.rendering.templating.SMSTemplateData
import rendering.Rendering
import com.ovoenergy.comms.model.MetadataV3
import com.ovoenergy.comms.model.sms.{ComposedSMSV4, OrchestratedSMSV3}

object Sms {

  def apply[F[_]: FlatMap](event: OrchestratedSMSV3)(
      implicit rendering: Rendering[F],
      store: Store[F],
      templates: Templates[F, Templates.Sms],
      hash: Hash[F],
      time: Time[F]): F[ComposedSMSV4] = {
    for {
      template <- templates.get(event.metadata.templateManifest)
      now <- time.now
      renderedSms <- rendering.renderSms(
        now,
        event.metadata.templateManifest,
        template,
        SMSTemplateData(event.templateData, event.customerProfile, event.recipientPhoneNumber))
      bodyUri <- store.upload(
        event.metadata.commId,
        event.metadata.traceToken,
        renderedSms.textBody)
      eventId <- hash(event.metadata.eventId ++ "-composed-sms")
      hashedComm <- hash(event)
    } yield
      ComposedSMSV4(
        metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventId),
        internalMetadata = event.internalMetadata,
        recipient = event.recipientPhoneNumber,
        textBody = bodyUri.renderString,
        expireAt = event.expireAt,
        hashedComm = hashedComm
      )
  }
}
