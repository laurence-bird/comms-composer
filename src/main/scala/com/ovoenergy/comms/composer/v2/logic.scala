package com.ovoenergy.comms.composer
package v2

import cats._, implicits._

import model.Email

// TODO change these imports to the shortened version
import com.ovoenergy.comms.model.email.{OrchestratedEmailV4, ComposedEmailV4}
import com.ovoenergy.comms.model.MetadataV3
import com.ovoenergy.comms.composer.rendering.templating.EmailTemplateData

object logic {

    def email[F[_]: Monad](event: OrchestratedEmailV4)(implicit rendering: Rendering[F], store: Store[F], templates: Templates[F, Templates.Email], hash: Hash[F], time: Time[F]): F[ComposedEmailV4]= {
      for {
        template <- templates.get(event.metadata.templateManifest)
        now      <- time.now
        renderedEmail <- rendering.renderEmail(now, event.metadata.templateManifest, template, EmailTemplateData(event.templateData, event.customerProfile, event.recipientEmailAddress))
        bodyUri <- store.upload(event.metadata.commId, event.metadata.traceToken, renderedEmail.htmlBody)
        subjectUri <- store.upload(event.metadata.commId, event.metadata.traceToken, renderedEmail.subject)
        textUri <- renderedEmail.textBody.traverse(x => store.upload(event.metadata.commId, event.metadata.traceToken, x))
        eventId <- hash(event.metadata.eventId)
        hashedComm <- hash(event)
      } yield ComposedEmailV4(
        metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventId),
        internalMetadata = event.internalMetadata,
        sender = Email.chooseSender(template).toString,
        recipient = event.recipientEmailAddress,
        subject = subjectUri.renderString,
        htmlBody = bodyUri.renderString,
        textBody = textUri.map(_.renderString),
        expireAt = event.expireAt,
        hashedComm = hashedComm
      )
    }









}
