package com.ovoenergy.comms.email

import java.util.UUID

import cats.{Id, ~>}
import com.ovoenergy.comms._
import com.ovoenergy.comms.model._
import org.scalatest.{FlatSpec, Matchers}

class ComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: ComposerA ~> Id = new (ComposerA ~> Id) {

    override def apply[A](op: ComposerA[A]): Id[A] = op match {
      case RetrieveTemplate(_, _) =>
        EmailTemplate(
          sender = None,
          subject = Mustache("Hello {{firstName}}"),
          htmlBody = Mustache("<h2>Thanks for your payment of £{{amount}}</h2>"),
          textBody = None,
          htmlFragments = Map.empty,
          textFragments = Map.empty
        )
      case Render(_, template, _, _, _) =>
        RenderedEmail(
          subject = template.subject.content.replaceAllLiterally("{{firstName}}", "Chris"),
          htmlBody = template.htmlBody.content.replaceAllLiterally("{{amount}}", "1.23"),
          textBody = None
        )
      case LookupSender(_, _) =>
        EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
    }
  }

  val incomingEvent = OrchestratedEmailV2(
    metadata = Metadata(
      createdAt = "2016-01-01T12:34:56Z",
      eventId = UUID.randomUUID().toString,
      customerId = "123-chris",
      traceToken = "abc",
      commManifest = CommManifest(CommType.Service, "test-template", "0.1"),
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None,
      triggerSource = "Laurence"
    ),
    recipientEmailAddress = "chris@foo.com",
    customerProfile = CustomerProfile("Joe", "Bloggs"),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI")
  )

  it should "compose an email" in {
    val event: ComposedEmail = Composer.program(incomingEvent).foldMap(testInterpreter)
    event.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
    event.recipient should be("chris@foo.com")
    event.subject should be("Hello Chris")
    event.htmlBody should be("<h2>Thanks for your payment of £1.23</h2>")
  }

}
