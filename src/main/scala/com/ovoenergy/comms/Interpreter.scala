package com.ovoenergy.comms

import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import cats.~>
import cats.syntax.either._

object Interpreter {

  type FailedOr[A] = Either[Failed, A]

  def build(incomingEvent: OrchestratedEmail): ComposerA ~> FailedOr = new (ComposerA ~> FailedOr) {

    override def apply[A](op: ComposerA[A]): FailedOr[A] = op match {
      case RetrieveTemplate(_, _) =>
        // TODO implement S3 repo
        Right(
          Template(
            sender = None,
            subject = Mustache("Hello {{firstName}}"),
            htmlBody = Mustache("<h2>Thanks for your payment of £{{amount}}</h2>"),
            textBody = None,
            htmlFragments = Map.empty,
            textFragments = Map.empty
          ))
      case Render(commManifest, template, data, customerProfile) =>
        Rendering
          .render(Clock.systemDefaultZone())(commManifest, template, data, customerProfile)
          .leftMap(reason => fail(reason, incomingEvent))
      case LookupSender(_, _) =>
        // TODO implement lookup logic
        Right(Sender("Ovo Energy", "no-reply@ovoenergy.com"))
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
