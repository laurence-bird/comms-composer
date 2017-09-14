package com.ovoenergy.comms.composer.print

import java.time.Instant
import java.util.UUID

import cats.{Id, ~>}
import com.ovoenergy.comms.composer.print.PrintComposer.PrintComposer
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.{ComposedPrint, OrchestratedPrint}
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import org.scalatest.{FlatSpec, Matchers}

class PrintComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: PrintComposerA ~> Id = new (PrintComposerA ~> Id) {
    val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())

    override def apply[A](op: PrintComposerA[A]) = op match {
      case RetrieveTemplate(_) =>
        PrintTemplate[Id](
          header = None,
          footer = None,
          body = HandlebarsTemplate("<h2>Thanks for your payment of £{{amount}}</h2>", requiredFields)
        )

      case Render(_, template) =>
        RenderedPrintHtml(
          htmlHeader = None,
          htmlFooter = None,
          htmlBody = template.body.rawExpandedContent.replaceAllLiterally("{{amount}}", "1.23")
        )

    }
  }

  val incomingEvent = OrchestratedPrint(
    metadata = MetadataV2(
      createdAt = Instant.now,
      eventId = UUID.randomUUID().toString,
      traceToken = "abc",
      Customer("customerId"),
      commManifest = CommManifest(model.Service, "test-template", "0.1"),
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None,
      triggerSource = "Laurence"
    ),
    address = CustomerAddress("line1", "line2", "London", "Middlesex", "HA9 8PH", "UK"),
    customerProfile = Some(CustomerProfile("Joe", "Bloggs")),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  it should "compose an email" in {
    val event: ComposedPrint = PrintComposer.program(incomingEvent).foldMap(testInterpreter)
    event.pdfIdentifier should be("<h2>Thanks for your payment of £1.23</h2>")
  }
}
