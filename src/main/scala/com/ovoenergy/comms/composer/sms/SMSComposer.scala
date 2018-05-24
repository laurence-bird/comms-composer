package com.ovoenergy.comms.composer.sms

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.composer.rendering.{HashFactory, SmsHashData}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

object SMSComposer {
  import scala.language.implicitConversions
  implicit def smsHashData(sms: OrchestratedSMSV3) =
    new SmsHashData(sms.metadata.deliverTo, sms.templateData, sms.metadata.templateManifest)

  type SMSComposer[A] = Free[SMSComposerA, A]

  def retrieveTemplate(incomingEvent: OrchestratedSMSV3): SMSComposer[SMSTemplate[Id]] =
    liftF(RetrieveTemplate(incomingEvent))

  def render(incomingEvent: OrchestratedSMSV3, template: SMSTemplate[Id]): SMSComposer[RenderedSMS] =
    liftF(Render(incomingEvent, template))

  def buildEvent(incomingEvent: OrchestratedSMSV3, renderedSMS: RenderedSMS): ComposedSMSV4 =
    ComposedSMSV4(
      metadata = MetadataV3.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      recipient = incomingEvent.recipientPhoneNumber,
      textBody = renderedSMS.textBody,
      expireAt = incomingEvent.expireAt,
      hashedComm = HashFactory.getHashedComm(incomingEvent)
    )

  def program(event: OrchestratedSMSV3) = {
    for {
      template <- retrieveTemplate(event)
      rendered <- render(event, template)
    } yield buildEvent(event, rendered)
  }

}
