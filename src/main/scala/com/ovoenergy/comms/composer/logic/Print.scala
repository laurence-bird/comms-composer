package com.ovoenergy.comms.composer
package logic

import cats.FlatMap
import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.composer.model.ComposerError
import com.ovoenergy.comms.model.{CompositionError, MetadataV3, TemplateData, TemplateManifest}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.composer.rendering.templating.{PrintTemplateData, TemplateDataWrapper}
import rendering.Rendering

object Print {

  private val blockedTemplateIds = Set(
    "82c00db7-16f0-4a19-984a-54b8789db436",
    "9173e5f3-53cd-48c4-9e51-784876210df6",
    "bb9b9cac-944e-4690-b6f3-1450c4e1b5fd",
    "1a10127e-82dc-426e-8fb5-8670c8a83ad7",
    "be69e7f4-160f-4cdc-846b-a108e94ddfe9",
    "f6986768-56c9-4ac2-b14e-19bae53c6be8",
    "6c264361-926d-407a-aff6-314b80b11e6a",
    "cdf527e1-b965-48a8-9d94-5d6014acb2f2",
    "621008be-5f0f-4c37-a2b7-5cb9d69bbe00",
    "403bfff5-f8ba-4ba1-babf-bf7604b60077",
    "0f8be8c3-e7c2-4f06-89ba-d998920cc15e",
    "21caf11f-9400-4dd7-8fb6-3a199381c7c9"
  )

  private def buildPrintTemplateData(event: OrchestratedPrintV2): PrintTemplateData =
    PrintTemplateData(event.templateData, event.customerProfile, event.address)

  def apply[F[_]](event: OrchestratedPrintV2)(
      implicit rendering: Rendering[F],
      store: Store[F],
      templates: Templates[F, Templates.Print],
      hash: Hash[F],
      time: Time[F],
      F: Sync[F]): F[ComposedPrintV2] = {
    for {
      _ <- {
        if(blockedTemplateIds.contains(event.metadata.templateManifest.id))
          F.raiseError(ComposerError(s"Template with id ${event.metadata.templateManifest.id} has been disabled", CompositionError))
        else F.unit
      }
      template <- templates.get(event.metadata.templateManifest)
      now <- time.now
      html <- rendering.renderPrintHtml(
        now,
        event.metadata.templateManifest,
        template,
        PrintTemplateData(event.templateData, event.customerProfile, event.address)
      )
      renderedPdf <- rendering.renderPrintPdf(html)
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

  def http[F[_]: FlatMap](templateManifest: TemplateManifest, data: Map[String, TemplateData])(
      implicit rendering: Rendering[F],
      store: Store[F],
      templates: Templates[F, Templates.Print],
      hash: Hash[F],
      time: Time[F]): F[model.Print.RenderedPdf] = {
    for {
      template <- templates.get(templateManifest)
      now <- time.now
      html <- rendering.renderPrintHtml(
        now,
        templateManifest,
        template,
        TemplateDataWrapper(data)
      )
      renderedPdf <- rendering.renderPrintPdf(html)
    } yield renderedPdf
  }
}
