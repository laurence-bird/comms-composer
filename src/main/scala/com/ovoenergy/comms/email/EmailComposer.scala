package com.ovoenergy.comms.email

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

object EmailComposer {

  type EmailComposer[A] = Free[EmailComposerA, A]

  def retrieveTemplate(incomingEvent: OrchestratedEmailV3): EmailComposer[EmailTemplate[Id]] =
    liftF(RetrieveTemplate(incomingEvent))

  def render(incomingEvent: OrchestratedEmailV3, template: EmailTemplate[Id]): EmailComposer[RenderedEmail] =
    liftF(Render(incomingEvent, template))

  def lookupSender(template: EmailTemplate[Id], commType: CommType): EmailComposer[EmailSender] =
    liftF(LookupSender(template, commType))

  def buildEvent(incomingEvent: OrchestratedEmailV3,
                 renderedEmail: RenderedEmail,
                 sender: EmailSender): ComposedEmailV2 =
    ComposedEmailV2(
      metadata = MetadataV2.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      sender = sender.toString,
      recipient = incomingEvent.recipientEmailAddress,
      subject = renderedEmail.subject,
      htmlBody = renderedEmail.htmlBody,
      textBody = renderedEmail.textBody,
      expireAt = incomingEvent.expireAt
    )

  def program(event: OrchestratedEmailV3) = {
    for {
      template <- retrieveTemplate(event)
      rendered <- render(event, template)
      sender <- lookupSender(template, event.metadata.commManifest.commType)
    } yield buildEvent(event, rendered, sender)
  }

}
