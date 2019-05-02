package com.ovoenergy.comms.composer
package logic

import cats._
import cats.data.OptionT
import cats.implicits._

import org.http4s.Uri

import com.ovoenergy.comms.model.{MetadataV3, TemplateData, InvalidTemplate}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}

import rendering.{TextRenderer, PdfRendering}
import model._

object Print {

  val bodyTemplateFragmentId = TemplateFragmentId("body.html")

  def printRecipientData(event: OrchestratedPrintV2) = {
    val customerAddress = event.address
    val address = TemplateData.fromMap(
      Map(
        "line1" -> Some(customerAddress.line1),
        "town" -> Some(customerAddress.town),
        "postcode" -> Some(customerAddress.postcode),
        "line2" -> customerAddress.line2,
        "county" -> customerAddress.county,
        "country" -> customerAddress.country
      ) collect {
        case (k, Some(v)) =>
          (k, TemplateData.fromString(v))
      }
    )

    Map(
      "address" -> address, // "address" needs to be there to support legacy templates where the address was in "address"
      "recipient" -> TemplateData.fromMap(Map("postalAddress" -> address))
    )
  }

  def apply[F[_]: FlatMap](
      store: Store[F],
      textRenderer: TextRenderer[F],
      pdfRenderer: PdfRendering[F],
      time: Time[F]
  )(event: OrchestratedPrintV2)(implicit ae: MonadError[F, Throwable]): F[ComposedPrintV2] = {

    val commId: CommId = event.metadata.commId
    val traceToken: TraceToken = event.metadata.traceToken
    val templateManifest = event.metadata.templateManifest

    def renderPrint(data: TemplateData): F[RenderedPrint] = {
      val toWatermark = event.metadata.canary;

      textRenderer
        .render(templateFragmentIdFor(templateManifest, TemplateFragmentType.Print.Body), data)
        .orRaiseError(
          new ComposerError(
            s"Template does not have the required print body fragment",
            InvalidTemplate)
        )
        .flatMap { fragment =>
          pdfRenderer.render(fragment, toWatermark)
        }
        .flatMap { pdfFragment =>
          store.upload(commId, traceToken, pdfFragment)
        }
        .map(uri => RenderedPrint(RenderedPrint.Body(uri)))
    }

    for {
      now <- time.now
      templateData = buildTemplateData(
        now,
        event.customerProfile,
        printRecipientData(event),
        event.templateData
      )
      renderedPdf <- renderPrint(templateData)
    } yield
      ComposedPrintV2(
        metadata = MetadataV3.fromSourceMetadata(
          "comms-composer",
          event.metadata,
          event.metadata.commId ++ "-composed-print"
        ),
        internalMetadata = event.internalMetadata,
        pdfIdentifier = renderedPdf.body.uri.renderString,
        expireAt = event.expireAt,
        hashedComm = "N/A",
      )
  }
}
