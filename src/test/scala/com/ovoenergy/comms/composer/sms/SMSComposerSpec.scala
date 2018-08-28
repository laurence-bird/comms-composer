package com.ovoenergy.comms.composer.sms

import java.time.Instant
import java.util.UUID

import cats.{Id, ~>}
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.util.Hash
import org.scalatest.{FlatSpec, Matchers}

class SMSComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: SMSComposerA ~> Id = new (SMSComposerA ~> Id) {
    val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())

    override def apply[A](op: SMSComposerA[A]) = op match {
      case RetrieveTemplate(_) =>
        SMSTemplate[Id](
          textBody = HandlebarsTemplate("Thanks for your payment of £{{amount}}", requiredFields)
        )
      case Render(_, template) =>
        RenderedSMS(
          textBody = template.textBody.rawExpandedContent.replaceAllLiterally("{{amount}}", "1.23")
        )

      case HashString(str) => hashedStr
    }
  }

  case class CustomerTransaction(bankCardNumber: Long, transactionTotal: Int, transactionDate: String)

  val hashedStr = "testing"

  val incomingEvent = OrchestratedSMSV3(
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
    recipientPhoneNumber = "+447123123456",
    customerProfile = Some(CustomerProfile("Joe", "Bloggs")),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  it should "compose an SMS" in {
    val event: ComposedSMSV4 = SMSComposer.program(incomingEvent).foldMap(testInterpreter)
    event.recipient should be("+447123123456")
    event.textBody should be("Thanks for your payment of £1.23")
  }

}
