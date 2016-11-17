package com.ovoenergy.comms

import java.time.OffsetDateTime
import java.util.UUID

import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.Channel.Email

object Composer {

  type StringOrA[A] = Either[String, A]
  type Composer[A] = Free[ComposerA, A]

  def retrieveTemplate(channel: Channel, commManifest: CommManifest): Composer[Template] =
    liftF(RetrieveTemplate(channel, commManifest))

  def render(template: Template,
             data: Map[String, String],
             customerProfile: CustomerProfile): Composer[RenderedEmail] =
    liftF(Render(template, data, customerProfile))

  def lookupSender(template: Template, commType: CommType): Composer[Sender] =
    liftF(LookupSender(template, commType))

  def validate(renderedEmail: RenderedEmail): Composer[Unit] =
    liftF(Validate(renderedEmail))

  def buildEvent(incomingEvent: OrchestratedEmail, renderedEmail: RenderedEmail, sender: Sender) = ComposedEmail(
    metadata = Metadata(
      timestampIso8601 = OffsetDateTime.now().toString,
      kafkaMessageId = UUID.randomUUID(),
      customerId = incomingEvent.metadata.customerId,
      transactionId = incomingEvent.metadata.transactionId,
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
      rendered <- render(template, event.data, event.customerProfile)
      sender <- lookupSender(template, event.commManifest.commType)
      _ <- validate(rendered)
    } yield buildEvent(event, rendered, sender)
  }

}
