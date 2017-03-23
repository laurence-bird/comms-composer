package com.ovoenergy.comms.sms

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

object SMSComposer {

  type SMSComposer[A] = Free[SMSComposerA, A]

  def retrieveTemplate(incomingEvent: OrchestratedSMS): SMSComposer[SMSTemplate[Id]] =
    liftF(RetrieveTemplate(incomingEvent))

  def render(incomingEvent: OrchestratedSMS, template: SMSTemplate[Id]): SMSComposer[RenderedSMS] =
    liftF(Render(incomingEvent, template))

  def buildEvent(incomingEvent: OrchestratedSMS, renderedSMS: RenderedSMS): ComposedSMS =
    ComposedSMS(
      metadata = Metadata.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      recipient = incomingEvent.recipientPhoneNumber,
      textBody = renderedSMS.textBody,
      expireAt = incomingEvent.expireAt
    )

  def program(event: OrchestratedSMS) = {
    for {
      template <- retrieveTemplate(event)
      rendered <- render(event, template)
    } yield buildEvent(event, rendered)
  }

}
