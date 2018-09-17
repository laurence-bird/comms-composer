package com.ovoenergy.comms.composer.sms

import cats.syntax.flatMap._
import cats.{FlatMap, Id}
import com.ovoenergy.comms.model.MetadataV3
import com.ovoenergy.comms.model.sms.{ComposedSMSV4, OrchestratedSMSV3}
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
trait SMSComposer[F[_]] {
  def retrieveTemplate(incomingEvent: OrchestratedSMSV3): F[SMSTemplate[Id]]

  def render(incomingEvent: OrchestratedSMSV3, template: SMSTemplate[Id]): F[RenderedSMS]

  def hashValue[A](a: A): F[String]
}

object SMSComposer {
  def program[F[_]: FlatMap](event: OrchestratedSMSV3)(implicit composer: SMSComposer[F]): F[ComposedSMSV4] = {
    for {
      template <- composer.retrieveTemplate(event)
      rendered <- composer.render(event, template)
      eventIdHash <- composer.hashValue(event.metadata.eventId)
      hashedComm <- composer.hashValue(event)
    } yield
      ComposedSMSV4(
        metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventIdHash),
        internalMetadata = event.internalMetadata,
        recipient = event.recipientPhoneNumber,
        textBody = rendered.textBody,
        expireAt = event.expireAt,
        hashedComm = hashedComm
      )
  }
}
