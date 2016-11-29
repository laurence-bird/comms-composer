package com.ovoenergy.comms.email

import java.time.OffsetDateTime
import java.util.UUID

import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.model.Channel._
import com.ovoenergy.comms.model._

object Composer {

  type StringOrA[A] = Either[String, A]
  type Composer[A] = Free[ComposerA, A]

  def retrieveTemplate(channel: Channel, commManifest: CommManifest): Composer[EmailTemplate] =
    liftF(RetrieveTemplate(channel, commManifest))

  def render(commManifest: CommManifest,
             template: EmailTemplate,
             data: Map[String, String],
             customerProfile: CustomerProfile,
             recipientEmailAddress: String): Composer[RenderedEmail] =
    liftF(Render(commManifest, template, data, customerProfile, recipientEmailAddress))

  def lookupSender(template: EmailTemplate, commType: CommType): Composer[EmailSender] =
    liftF(LookupSender(template, commType))

  def buildEvent(incomingEvent: OrchestratedEmail, renderedEmail: RenderedEmail, sender: EmailSender) = ComposedEmail(
    metadata = Metadata(
      createdAt = OffsetDateTime.now().toString,
      eventId = UUID.randomUUID().toString,
      customerId = incomingEvent.metadata.customerId,
      traceToken = incomingEvent.metadata.traceToken,
      friendlyDescription = incomingEvent.metadata.friendlyDescription,
      source = "comms-composer",
      canary = incomingEvent.metadata.canary,
      sourceMetadata = Some(incomingEvent.metadata)
    ),
    sender = sender.toString,
    recipient = incomingEvent.recipientEmailAddress,
    subject = renderedEmail.subject,
    htmlBody = renderedEmail.htmlBody,
    textBody = renderedEmail.textBody
  )

  def program(event: OrchestratedEmail): Composer[ComposedEmail] = {
    for {
      template <- retrieveTemplate(Email, event.commManifest)
      rendered <- render(event.commManifest, template, event.data, event.customerProfile, event.recipientEmailAddress)
      sender <- lookupSender(template, event.commManifest.commType)
    } yield buildEvent(event, rendered, sender)
  }

}
