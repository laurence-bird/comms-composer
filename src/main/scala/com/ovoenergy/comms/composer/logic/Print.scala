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

  def apply[F[_]: FlatMap](event: OrchestratedPrintV2)(
      implicit ae: MonadError[F, Throwable],
      store: Store[F],
      textRenderer: TextRenderer[F],
      pdfRenderer: PdfRendering[F],
      time: Time[F]): F[ComposedPrintV2] = {

    val commId: CommId = event.metadata.commId
    val traceToken: TraceToken = event.metadata.traceToken

    // TODO
    val recipientData = {
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
      // "address" needs to be there to support legacy templates where the address was in "address"
      Map(
        "address" -> address,
        "recipient" -> TemplateData.fromMap(Map("postalAddress" -> address)))
    }

    def renderPrint(data: Map[String, TemplateData]): F[RenderedPrint] = {
      val toWatermark = event.metadata.canary;

      textRenderer
        .render(bodyTemplateFragmentId, data)
        .orRaiseError(
          new ComposerError(
            s"Template does not have the required ${bodyTemplateFragmentId.value} fragment",
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

    ???
    for {
      now <- time.now
      templateData = buildTemplateData(
        now,
        event.customerProfile,
        recipientData ++ event.templateData
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
