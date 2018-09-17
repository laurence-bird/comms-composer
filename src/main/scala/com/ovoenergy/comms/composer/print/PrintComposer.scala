package com.ovoenergy.comms.composer.print

import cats.{FlatMap, Id}
import com.ovoenergy.comms.composer.rendering.templating.{CommTemplateData, PrintTemplateData, TemplateDataWrapper}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.model.{MetadataV3, TemplateData, TemplateManifest}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

trait PrintComposer[F[_]] {
  def retrieveTemplate(templateManifest: TemplateManifest): F[PrintTemplate[Id]]

  def renderPrintHtml(commTemplateData: CommTemplateData,
                      template: PrintTemplate[Id],
                      templateManifest: TemplateManifest): F[RenderedPrintHtml]

  def renderPrintPdf(renderedPrintHtml: RenderedPrintHtml, templateManifest: TemplateManifest): F[RenderedPrintPdf]

  def persistRenderedPdf(event: OrchestratedPrintV2, renderedPrintPdf: RenderedPrintPdf)

  def hashValue[A](a: A): F[String]
}

object PrintComposer {

  private def buildPrintTemplateData(event: OrchestratedPrintV2): PrintTemplateData =
    PrintTemplateData(event.templateData, event.customerProfile, event.address)

  def program[F[_]](event: OrchestratedPrintV2)(implicit composer: PrintComposer[F]): F[ComposedPrintV2] = {
    for {
      template <- composer.retrieveTemplate(event.metadata.templateManifest)
      renderedPrintHtml <- composer.renderPrintHtml(buildPrintTemplateData(event),
                                                    template,
                                                    event.metadata.templateManifest)
      renderedPrintPdf <- composer.renderPrintPdf(renderedPrintHtml, event.metadata.templateManifest)
      pdfIdentifier <- composer.persistRenderedPdf(event, renderedPrintPdf)
      eventIdHash <- composer.hashValue(event.metadata.eventId)
      hashedComm <- composer.hashValue(event)
    } yield
      ComposedPrintV2(
        metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventIdHash),
        internalMetadata = event.internalMetadata,
        pdfIdentifier = pdfIdentifier,
        hashedComm = hashedComm,
        expireAt = event.expireAt
      )
  }

  def httpProgram[F[_]: FlatMap](templateManifest: TemplateManifest, data: Map[String, TemplateData])(
      implicit composer: PrintComposer[F]): F[RenderedPrintPdf] = {
    for {
      template <- composer.retrieveTemplate(templateManifest)
      renderedPrintHtml <- composer.renderPrintHtml(TemplateDataWrapper(data), template, templateManifest)
      renderedPrintPdf <- composer.renderPrintPdf(renderedPrintHtml, templateManifest)
    } yield renderedPrintPdf
  }
}
