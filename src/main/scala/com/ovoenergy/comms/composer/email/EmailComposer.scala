package com.ovoenergy.comms.composer.email

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.composer.rendering.{EmailHashData, HashFactory}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

object EmailComposer {

  import scala.language.implicitConversions

  implicit def emailHashData(email: OrchestratedEmailV4): EmailHashData =
    new EmailHashData(email.metadata.deliverTo, email.templateData, email.metadata.templateManifest)

  type EmailComposer[A] = Free[EmailComposerA, A]

  def retrieveTemplate(incomingEvent: OrchestratedEmailV4): EmailComposer[EmailTemplate[Id]] =
    liftF(RetrieveTemplate(incomingEvent))

  def render(incomingEvent: OrchestratedEmailV4, template: EmailTemplate[Id]): EmailComposer[RenderedEmail] =
    liftF(Render(incomingEvent, template))

  def lookupSender(template: EmailTemplate[Id], commType: CommType): EmailComposer[EmailSender] =
    liftF(LookupSender(template, commType))

  def buildEvent(incomingEvent: OrchestratedEmailV4,
                 renderedEmail: RenderedEmail,
                 sender: EmailSender): ComposedEmailV4 =
    ComposedEmailV4(
      metadata = MetadataV3.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      sender = sender.toString,
      recipient = incomingEvent.recipientEmailAddress,
      subject = renderedEmail.subject,
      htmlBody = renderedEmail.htmlBody,
      textBody = renderedEmail.textBody,
      expireAt = incomingEvent.expireAt,
      hashedComm = HashFactory.getHashedComm(incomingEvent)
    )

  def program(event: OrchestratedEmailV4) = {
    for {
      template <- retrieveTemplate(event)
      rendered <- render(event, template)
      sender <- lookupSender(template, event.metadata.commType)
    } yield buildEvent(event, rendered, sender)
  }

}
