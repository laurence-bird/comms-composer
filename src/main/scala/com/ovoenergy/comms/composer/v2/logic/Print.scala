package com.ovoenergy.comms.composer
package v2
package logic

import cats.FlatMap, cats.implicits._

import com.ovoenergy.comms.model.{MetadataV3, TemplateData, TemplateManifest}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}

import com.ovoenergy.comms.composer.rendering.templating.{PrintTemplateData, TemplateDataWrapper}

object Print {

  private def buildPrintTemplateData(event: OrchestratedPrintV2): PrintTemplateData =
    PrintTemplateData(event.templateData, event.customerProfile, event.address)

  def apply[F[_] : FlatMap](event: OrchestratedPrintV2)(implicit rendering: Rendering[F], store: Store[F], templates: Templates[F, Templates.Print], hash: Hash[F], time: Time[F]): F[ComposedPrintV2] = {
    for {
      template <- templates.get(event.metadata.templateManifest)
      now <- time.now
      html <- rendering.renderPrintHtml(
        now,
        event.metadata.templateManifest,
        template,
        PrintTemplateData(event.templateData, event.customerProfile, event.address)
      )
      renderedPdf <- rendering.renderPrintPdf(html, event.metadata.templateManifest)
      pdfUri <- store.upload(event.metadata.commId, event.metadata.traceToken, renderedPdf.fragment)
      eventIdHash <- hash.apply(event.metadata.eventId)
      hashedComm <- hash.apply(event)
    } yield
      ComposedPrintV2(
        metadata = MetadataV3.fromSourceMetadata("comms-composer", event.metadata, eventIdHash),
        internalMetadata = event.internalMetadata,
        pdfIdentifier = pdfUri.renderString,
        hashedComm = hashedComm,
        expireAt = event.expireAt
      )
  }

  def http[F[_]: FlatMap](templateManifest: TemplateManifest, data: Map[String, TemplateData])(implicit rendering: Rendering[F], store: Store[F], templates: Templates[F, Templates.Print], hash: Hash[F], time: Time[F]): F[model.Print.RenderedPdf] = {
    for {
      template <- templates.get(templateManifest)
      now <- time.now
      html <- rendering.renderPrintHtml(
        now,
        templateManifest,
        template,
        TemplateDataWrapper(data)
      )
      renderedPdf <- rendering.renderPrintPdf(html, templateManifest)
    } yield renderedPdf
  }
}
