package com.ovoenergy.comms.composer.print

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.model.MetadataV2
import com.ovoenergy.comms.model.print.{ComposedPrint, OrchestratedPrint}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

object PrintComposer {

  type PrintComposer[A] = Free[PrintComposerA, A]
  type PdfReference = String

  def retrieveTemplate(incomingEvent: OrchestratedPrint): PrintComposer[PrintTemplate[Id]] = {
    liftF(RetrieveTemplate(incomingEvent))
  }

  def renderPrintHtml(incomingEvent: OrchestratedPrint,
                      template: PrintTemplate[Id]): PrintComposer[RenderedPrintHtml] = {
    liftF(RenderPrintHtml(incomingEvent, template))
  }

  def renderPrintPdf(event: OrchestratedPrint, renderedPrintHtml: RenderedPrintHtml): PrintComposer[RenderedPrintPdf] = {
    liftF(RenderPrintPdf(event, renderedPrintHtml))
  }

  def persistRenderedPdf(event: OrchestratedPrint, renderedPrintPdf: RenderedPrintPdf): PrintComposer[PdfReference] = {
    liftF(PersistRenderedPdf(event, renderedPrintPdf))
  }

  def buildEvent(incomingEvent: OrchestratedPrint, pdfIdentifier: String): ComposedPrint =
    ComposedPrint(
      metadata = MetadataV2.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      pdfIdentifier = pdfIdentifier,
      expireAt = incomingEvent.expireAt
    )

  def program(event: OrchestratedPrint): Free[PrintComposerA, ComposedPrint] = {
    for {
      template <- retrieveTemplate(event)
      renderedPrintHtml <- renderPrintHtml(event, template)
      renderedPrintPdf <- renderPrintPdf(event, renderedPrintHtml)
      pdfIdentifier <- persistRenderedPdf(event, renderedPrintPdf)
    } yield buildEvent(event, pdfIdentifier)
  }

}
