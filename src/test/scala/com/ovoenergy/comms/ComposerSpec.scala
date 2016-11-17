package com.ovoenergy.comms

import java.util.UUID

import org.scalatest.{FlatSpec, Matchers}
import cats.{Id, ~>}

class ComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: ComposerA ~> Id = new (ComposerA ~> Id) {

    override def apply[A](op: ComposerA[A]): Id[A] = op match {
      case RetrieveTemplate(_, _) =>
        Template(
          sender = None,
          subject = Mustache("Hello {{firstName}}"),
          htmlBody = Mustache("<h2>Thanks for your payment of £{{amount}}</h2>"),
          textBody = None,
          htmlFragments = Map.empty,
          textFragments = Map.empty
        )
      case Render(template, _, _) =>
        RenderedEmail(
          subject = template.subject.content.replaceAllLiterally("{{firstName}}", "Chris"),
          htmlBody = template.htmlBody.content.replaceAllLiterally("{{amount}}", "1.23"),
          textBody = None
        )
      case LookupSender(_, _) =>
        Sender("Ovo Energy", "no-reply@ovoenergy.com")
      case Validate(_) =>
        ()
    }
  }

  val incomingEvent = OrchestratedEmail(
    metadata = Metadata(
      timestampIso8601 = "2016-01-01T12:34:56Z",
      kafkaMessageId = UUID.randomUUID(),
      customerId = "123-chris",
      transactionId = "abc",
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None
    ),
    commManifest = CommManifest(CommType.Service, "test-template", "0.1"),
    recipientEmailAddress = "chris@foo.com",
    customerProfile = CustomerProfile("Joe", "Bloggs"),
    data = Map.empty
  )

  it should "compose an email" in {
    val event: ComposedEmail = Composer.program(incomingEvent).foldMap(testInterpreter)
    event.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
    event.recipient should be("chris@foo.com")
    event.subject should be("Hello Chris")
    event.htmlBody should be("<h2>Thanks for your payment of £1.23</h2>")
  }

}
