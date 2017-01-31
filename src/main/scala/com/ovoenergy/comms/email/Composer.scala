package com.ovoenergy.comms.email

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
             data: Map[String, TemplateData],
             customerProfile: CustomerProfile,
             recipientEmailAddress: String): Composer[RenderedEmail] =
    liftF(Render(commManifest, template, data, customerProfile, recipientEmailAddress))

  def lookupSender(template: EmailTemplate, commType: CommType): Composer[EmailSender] =
    liftF(LookupSender(template, commType))

  def buildEvent(incomingEvent: OrchestratedEmailV2, renderedEmail: RenderedEmail, sender: EmailSender) =
    ComposedEmail(
      metadata = Metadata.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      sender = sender.toString,
      recipient = incomingEvent.recipientEmailAddress,
      subject = renderedEmail.subject,
      htmlBody = renderedEmail.htmlBody,
      textBody = renderedEmail.textBody
    )

  def program(event: OrchestratedEmailV2) = {
    for {
      template <- retrieveTemplate(Email, event.metadata.commManifest)
      rendered <- render(event.metadata.commManifest,
                         template,
                         event.templateData,
                         event.customerProfile,
                         event.recipientEmailAddress)
      sender <- lookupSender(template, event.metadata.commManifest.commType)
    } yield buildEvent(event, rendered, sender)
  }

}
