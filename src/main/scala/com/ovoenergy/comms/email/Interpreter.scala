package com.ovoenergy.comms.email

import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms._
import com.ovoenergy.comms.repo.{S3Client, S3TemplateRepo}

object Interpreter {

  type FailedOr[A] = Either[Failed, A]

  def build(s3client: S3Client)(incomingEvent: OrchestratedEmail): ComposerA ~> FailedOr =
    new (ComposerA ~> FailedOr) {
      override def apply[A](op: ComposerA[A]): FailedOr[A] = op match {
        case RetrieveTemplate(channel, commManifest) =>
          // only supporting email for now
          S3TemplateRepo.getEmailTemplate(commManifest).run(s3client).leftMap(reason => fail(reason, incomingEvent))
        case Render(commManifest, template, data, customerProfile) =>
          Rendering
            .renderEmail(Clock.systemDefaultZone())(commManifest, template, data, customerProfile)
            .leftMap(reason => fail(reason, incomingEvent))
        case LookupSender(template, commType) =>
          Right(SenderLogic.chooseSender(template, commType))
      }
    }

  private def fail(reason: String, incomingEvent: OrchestratedEmail): Failed = Failed(
    // TODO add a convenience constructor to Metadata in comms-kafka-messages
    Metadata(
      OffsetDateTime.now().toString,
      UUID.randomUUID(),
      incomingEvent.metadata.customerId,
      incomingEvent.metadata.transactionId,
      incomingEvent.metadata.friendlyDescription,
      "comms-composer",
      incomingEvent.metadata.canary,
      Some(incomingEvent.metadata)
    ),
    reason
  )

}
