package com.ovoenergy.comms.composer.email

import java.time.Instant
import java.util.UUID

import cats.{Id, ~>}
import cats.Cartesian
import com.ovoenergy.comms._
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.templates.model.{EmailSender, HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.util.Hash
import org.scalatest.{FlatSpec, Matchers}

class EmailComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: EmailComposerA ~> Id = new (EmailComposerA ~> Id) {
    val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())

    override def apply[A](op: EmailComposerA[A]) = op match {
      case RetrieveTemplate(_) =>
        EmailTemplate[Id](
          sender = None,
          subject = HandlebarsTemplate("Hello {{firstName}}", requiredFields),
          htmlBody = HandlebarsTemplate("<h2>Thanks for your payment of £{{amount}}</h2>", requiredFields),
          textBody = None
        )
      case Render(_, template) =>
        RenderedEmail(
          subject = template.subject.rawExpandedContent.replaceAllLiterally("{{firstName}}", "Chris"),
          htmlBody = template.htmlBody.rawExpandedContent.replaceAllLiterally("{{amount}}", "1.23"),
          textBody = None
        )
      case LookupSender(_) =>
        EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
    }
  }

  val incomingEvent = OrchestratedEmailV4(
    metadata = MetadataV3(
      createdAt = Instant.now,
      eventId = UUID.randomUUID().toString,
      traceToken = "abc",
      deliverTo = Customer("customerId"),
      templateManifest = TemplateManifest(Hash("test-template"), "0.1"),
      commId = "1234",
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None,
      triggerSource = "Laurence"
    ),
    recipientEmailAddress = "chris@foo.com",
    customerProfile = Some(CustomerProfile("Joe", "Bloggs")),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  it should "compose an email" in {
    val event: ComposedEmailV4 = EmailComposer.program(incomingEvent).foldMap(testInterpreter)
    event.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
    event.recipient should be("chris@foo.com")
    event.subject should be("Hello Chris")
    event.htmlBody should be("<h2>Thanks for your payment of £1.23</h2>")
  }

}
