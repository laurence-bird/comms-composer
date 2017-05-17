package com.ovoenergy.comms.sms

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

object SMSComposer {

  type SMSComposer[A] = Free[SMSComposerA, A]

  def retrieveTemplate(incomingEvent: OrchestratedSMSV2): SMSComposer[SMSTemplate[Id]] =
    liftF(RetrieveTemplate(incomingEvent))

  def render(incomingEvent: OrchestratedSMSV2, template: SMSTemplate[Id]): SMSComposer[RenderedSMS] =
    liftF(Render(incomingEvent, template))

  def buildEvent(incomingEvent: OrchestratedSMSV2, renderedSMS: RenderedSMS): ComposedSMSV2 =
    ComposedSMSV2(
      metadata = MetadataV2.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      recipient = incomingEvent.recipientPhoneNumber,
      textBody = renderedSMS.textBody,
      expireAt = incomingEvent.expireAt
    )

  def program(event: OrchestratedSMSV2) = {
    for {
      template <- retrieveTemplate(event)
      rendered <- render(event, template)
    } yield buildEvent(event, rendered)
  }

}
