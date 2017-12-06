package com.ovoenergy.comms.composer.print

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.composer.rendering.templating.{
  BuildHandlebarsData,
  CommTemplateData,
  PrintTemplateData,
  TemplateDataWrapper
}
import com.ovoenergy.comms.composer.rendering.{HashFactory, PrintHashData}
import com.ovoenergy.comms.model.{CommManifest, MetadataV2, TemplateData}
import com.ovoenergy.comms.model.print.{ComposedPrint, OrchestratedPrint}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

object PrintComposer {
  import scala.language.implicitConversions
  implicit def printHashData(print: OrchestratedPrint) =
    new PrintHashData(print.customerProfile, print.address, print.templateData, print.metadata.commManifest)

  type PrintComposer[A] = Free[PrintComposerA, A]
  type PdfReference = String

  def retrieveTemplate(commManifest: CommManifest): PrintComposer[PrintTemplate[Id]] = {
    liftF(RetrieveTemplate(commManifest))
  }

  def renderPrintHtml(commTemplateData: CommTemplateData,
                      template: PrintTemplate[Id],
                      commManifest: CommManifest): PrintComposer[RenderedPrintHtml] = {
    liftF(RenderPrintHtml(commTemplateData, template, commManifest))
  }

  def renderPrintPdf(renderedPrintHtml: RenderedPrintHtml): PrintComposer[RenderedPrintPdf] = {
    liftF(RenderPrintPdf(renderedPrintHtml))
  }

  def persistRenderedPdf(event: OrchestratedPrint, renderedPrintPdf: RenderedPrintPdf): PrintComposer[PdfReference] = {
    liftF(PersistRenderedPdf(event, renderedPrintPdf))
  }

  def buildEvent(incomingEvent: OrchestratedPrint, pdfIdentifier: String): ComposedPrint = {
    ComposedPrint(
      metadata = MetadataV2.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      pdfIdentifier = pdfIdentifier,
      hashedComm = HashFactory.getHashedComm(incomingEvent),
      expireAt = incomingEvent.expireAt
    )
  }

  private def buildPrintTemplateData(event: OrchestratedPrint): PrintTemplateData =
    PrintTemplateData(event.templateData, event.customerProfile, event.address)

  def program(event: OrchestratedPrint): Free[PrintComposerA, ComposedPrint] = {
    for {
      template <- retrieveTemplate(event.metadata.commManifest)
      renderedPrintHtml <- renderPrintHtml(buildPrintTemplateData(event), template, event.metadata.commManifest)
      renderedPrintPdf <- renderPrintPdf(renderedPrintHtml)
      pdfIdentifier <- persistRenderedPdf(event, renderedPrintPdf)
    } yield buildEvent(event, pdfIdentifier)
  }

  def httpProgram(commManifest: CommManifest,
                  data: Map[String, TemplateData]): Free[PrintComposerA, RenderedPrintPdf] = {
    for {
      template <- retrieveTemplate(commManifest)
      renderedPrintHtml <- renderPrintHtml(TemplateDataWrapper(data), template, commManifest)
      renderedPrintPdf <- renderPrintPdf(renderedPrintHtml)
    } yield renderedPrintPdf
  }
}
