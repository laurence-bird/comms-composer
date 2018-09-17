package com.ovoenergy.comms.composer
package email

import cats.FlatMap
import com.ovoenergy.comms.composer.rendering.EventId
import com.ovoenergy.comms.model.MetadataV3
import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import cats.implicits._
import com.ovoenergy.comms.composer.v2._
import com.ovoenergy.comms.composer.rendering.templating.EmailTemplateData

object Program {
  def apply[F[_]: FlatMap](event: OrchestratedEmailV4)(implicit rendering: Rendering[F], store: Store[F], templates: Templates[F, Templates.Email], hash: Hash[F], time: Time[F]): F[ComposedEmailV4]= {
    for {
      template <- templates.get(event.metadata.templateManifest)
      renderedEmail <- rendering.renderEmail(time, event.metadata.templateManifest, template, EmailTemplateData(event.templateData, event.customerProfile, event.recipientEmailAddress))
      bodyUri <- store.upload(event.metadata.commId, event.metadata.traceToken, renderedEmail.htmlBody)
      subjectUri <- store.upload(event.metadata.commId, event.metadata.traceToken, renderedEmail.subject)
      textUri <- renderedEmail.textBody.traverse(x => store.upload(event.metadata.commId, event.metadata.traceToken, x))
      eventId <- hash(EventId(event.metadata.eventId))
      hashedComm <- hash(event)
    } yield ComposedEmailV4(
      metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventId),
      internalMetadata = event.internalMetadata,
      sender = SenderLogic.chooseSender(template).toString,
      recipient = event.recipientEmailAddress,
      subject = subjectUri.renderString,
      htmlBody = bodyUri.renderString,
      textBody = textUri.map(_.renderString),
      expireAt = event.expireAt,
      hashedComm = hashedComm
    )
  }
}
