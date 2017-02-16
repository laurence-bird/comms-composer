package com.ovoenergy.comms.email

import java.util.UUID

import cats.data.Validated.Valid
import cats.{Id, ~>}
import com.ovoenergy.comms._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.{EmailSender, HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import org.scalatest.{FlatSpec, Matchers}

class ComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: ComposerA ~> Id = new (ComposerA ~> Id) {
    val requiredFields = Valid(RequiredTemplateData.obj(Map[String, RequiredTemplateData]()))

    override def apply[A](op: ComposerA[A]) = op match {
      case RetrieveTemplate(_, _) =>
        EmailTemplate[Id](
          sender = None,
          subject = HandlebarsTemplate("Hello {{firstName}}", requiredFields),
          htmlBody = HandlebarsTemplate("<h2>Thanks for your payment of £{{amount}}</h2>", requiredFields),
          textBody = None
        )
      case Render(_, template, _, _, _) =>
        RenderedEmail(
          subject = template.subject.rawExpandedContent.replaceAllLiterally("{{firstName}}", "Chris"),
          htmlBody = template.htmlBody.rawExpandedContent.replaceAllLiterally("{{amount}}", "1.23"),
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
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  it should "compose an email" in {
    val event: ComposedEmail = Composer.program(incomingEvent).foldMap(testInterpreter)
    event.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
    event.recipient should be("chris@foo.com")
    event.subject should be("Hello Chris")
    event.htmlBody should be("<h2>Thanks for your payment of £1.23</h2>")
  }

}
