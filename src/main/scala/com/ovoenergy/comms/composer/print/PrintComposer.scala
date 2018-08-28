package com.ovoenergy.comms.composer.print

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.composer.rendering.templating.{CommTemplateData, PrintTemplateData, TemplateDataWrapper}
import com.ovoenergy.comms.composer.rendering.{HashFactory, PrintHashData}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

object PrintComposer {
  import scala.language.implicitConversions
  implicit def printHashData(print: OrchestratedPrintV2) =
    new PrintHashData(print.customerProfile, print.address, print.templateData, print.metadata.templateManifest)

  type PrintComposer[A] = Free[PrintComposerA, A]
  type PdfReference = String

  def retrieveTemplate(templateManifest: TemplateManifest): PrintComposer[PrintTemplate[Id]] = {
    liftF(RetrieveTemplate(templateManifest))
  }

  def renderPrintHtml(commTemplateData: CommTemplateData,
                      template: PrintTemplate[Id],
                      templateManifest: TemplateManifest): PrintComposer[RenderedPrintHtml] = {
    liftF(RenderPrintHtml(commTemplateData, template, templateManifest))
  }

  def renderPrintPdf(renderedPrintHtml: RenderedPrintHtml,
                     templateManifest: TemplateManifest): PrintComposer[RenderedPrintPdf] = {
    liftF(RenderPrintPdf(renderedPrintHtml, templateManifest))
  }

  def persistRenderedPdf(event: OrchestratedPrintV2, renderedPrintPdf: RenderedPrintPdf): PrintComposer[PdfReference] = {
    liftF(PersistRenderedPdf(event, renderedPrintPdf))
  }

  def hashString(str: String): PrintComposer[String] = {
    liftF(HashString(str))
  }

  def buildEvent(incomingEvent: OrchestratedPrintV2, pdfIdentifier: String, eventId: String): ComposedPrintV2 = {
    ComposedPrintV2(
      metadata = MetadataV3.fromSourceMetadata("comms-composer", incomingEvent.metadata, eventId),
      internalMetadata = incomingEvent.internalMetadata,
      pdfIdentifier = pdfIdentifier,
      hashedComm = HashFactory.getHashedComm(incomingEvent),
      expireAt = incomingEvent.expireAt
    )
  }

  private def buildPrintTemplateData(event: OrchestratedPrintV2): PrintTemplateData =
    PrintTemplateData(event.templateData, event.customerProfile, event.address)

  def program(event: OrchestratedPrintV2): Free[PrintComposerA, ComposedPrintV2] = {
    for {
      template <- retrieveTemplate(event.metadata.templateManifest)
      renderedPrintHtml <- renderPrintHtml(buildPrintTemplateData(event), template, event.metadata.templateManifest)
      renderedPrintPdf <- renderPrintPdf(renderedPrintHtml, event.metadata.templateManifest)
      pdfIdentifier <- persistRenderedPdf(event, renderedPrintPdf)
      eventIdHash <- hashString(event.metadata.eventId)
    } yield buildEvent(event, pdfIdentifier, eventIdHash)
  }

  def httpProgram(templateManifest: TemplateManifest,
                  data: Map[String, TemplateData]): Free[PrintComposerA, RenderedPrintPdf] = {
    for {
      template <- retrieveTemplate(templateManifest)
      renderedPrintHtml <- renderPrintHtml(TemplateDataWrapper(data), template, templateManifest)
      renderedPrintPdf <- renderPrintPdf(renderedPrintHtml, templateManifest)
    } yield renderedPrintPdf
  }
}
