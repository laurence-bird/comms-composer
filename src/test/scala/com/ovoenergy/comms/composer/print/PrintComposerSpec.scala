package com.ovoenergy.comms.composer.print

import java.time.Instant
import java.util.UUID

import cats.{Id, ~>}
import com.ovoenergy.comms.composer.print.PrintComposer.PrintComposer
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.Brand.Ovo
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.util.Hash
import org.scalatest.{FlatSpec, Matchers}

class PrintComposerSpec extends FlatSpec with Matchers {

  val testInterpreter: PrintComposerA ~> Id = new (PrintComposerA ~> Id) {
    val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())

    override def apply[A](op: PrintComposerA[A]) = op match {
      case RetrieveTemplate(_) =>
        PrintTemplate[Id](
          body = HandlebarsTemplate("<h2>Thanks for your payment of £{{amount}}</h2>", requiredFields)
        )

      case RenderPrintHtml(_, template, _) =>
        RenderedPrintHtml(
          htmlBody = template.body.rawExpandedContent.replaceAllLiterally("{{amount}}", "1.23")
        )

      case RenderPrintPdf(r, _) => RenderedPrintPdf("Hello".getBytes())
      case PersistRenderedPdf(incomingEvent, r) => "PdfIdentifier"
    }
  }

  val incomingEvent = OrchestratedPrintV2(
    metadata = MetadataV3(
      createdAt = Instant.now,
      eventId = UUID.randomUUID().toString,
      traceToken = "abc",
      deliverTo = Customer("customerId"),
      templateManifest = TemplateManifest(Hash("test-template"), "0.1"),
      commId = "1234",
      commName = "test-template",
      commType = model.Service,
      brand = Ovo,
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None,
      triggerSource = "Laurence"
    ),
    address = CustomerAddress("line1", Some("line2"), "London", Some("Middlesex"), "HA9 8PH", Some("UK")),
    customerProfile = Some(CustomerProfile("Joe", "Bloggs")),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  it should "compose an email" in {
    val event: ComposedPrintV2 = PrintComposer.program(incomingEvent).foldMap(testInterpreter)
    event.pdfIdentifier should be("PdfIdentifier")
    event.internalMetadata should be(incomingEvent.internalMetadata)
  }
}
