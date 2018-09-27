package com.ovoenergy.comms.composer
package logic

import cats.Monad
import cats.implicits._
import com.ovoenergy.comms.model.MetadataV3
import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import com.ovoenergy.comms.composer.rendering.templating.EmailTemplateData
import rendering.Rendering

object Email {
  def apply[F[_]: Monad](event: OrchestratedEmailV4)(
      implicit rendering: Rendering[F],
      store: Store[F],
      templates: Templates[F, Templates.Email],
      hash: Hash[F],
      time: Time[F]): F[ComposedEmailV4] = {
    for {
      template <- templates.get(event.metadata.templateManifest)
      now <- time.now
      renderedEmail <- rendering.renderEmail(
        now,
        event.metadata.templateManifest,
        template,
        EmailTemplateData(event.templateData, event.customerProfile, event.recipientEmailAddress))
      bodyUri <- store.upload(event.metadata.commId, event.metadata.traceToken, renderedEmail.html)
      subjectUri <- store.upload(
        event.metadata.commId,
        event.metadata.traceToken,
        renderedEmail.subject)
      textUri <- renderedEmail.text.traverse(x =>
        store.upload(event.metadata.commId, event.metadata.traceToken, x))
      eventId <- hash(event.metadata.eventId)
      hashedComm <- hash(event)
    } yield
      ComposedEmailV4(
        metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventId),
        internalMetadata = event.internalMetadata,
        sender = model.Email.chooseSender(template).toString,
        recipient = event.recipientEmailAddress,
        subject = subjectUri.renderString,
        htmlBody = bodyUri.renderString,
        textBody = textUri.map(_.renderString),
        expireAt = event.expireAt,
        hashedComm = hashedComm
      )

  }
}
