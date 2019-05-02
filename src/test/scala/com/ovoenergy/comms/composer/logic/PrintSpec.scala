package com.ovoenergy.comms.composer
package logic

import java.{util => ju}
import scala.language.reflectiveCalls

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.implicits._

import org.http4s.Uri

import org.scalacheck._

import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.TemplateData

import model._
import rendering._
import mocks._

import Arbitraries._

class PrintSpec extends UnitSpec {

  val time = Time[IO]

  behavior of "Print"

  it should "fail when the body template fragment does not exist" in forAll {
    (rf: RenderedFragment, rPdfF: RenderedPdfFragment, rPdfFUri: Uri, event: OrchestratedPrintV2) =>
      val store = MockedStore(rPdfF -> rPdfFUri)
      val textRenderer = MockedTextRenderer.empty
      val pdfRenderer = MockedPdfRendering.empty

      val result = Print[IO](store, textRenderer, pdfRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "succeeded when the body template fragment exists" in forAll {
    (rf: RenderedFragment, rPdfF: RenderedPdfFragment, rPdfFUri: Uri, event: OrchestratedPrintV2) =>
      val store = MockedStore(rPdfF -> rPdfFUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Print.Body) -> rf
      )
      val pdfRenderer = MockedPdfRendering(
        rf -> rPdfF
      )

      val result = Print[IO](store, textRenderer, pdfRenderer, time)(event).attempt.futureValue

      result shouldBe a[Right[_, _]]
  }

  it should "fail when storing the fragment fails" in forAll {
    (rf: RenderedFragment, rPdfF: RenderedPdfFragment, event: OrchestratedPrintV2) =>
      val store = MockedStore.failing(new Exception("Oh no"))
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Print.Body) -> rf
      )
      val pdfRenderer = MockedPdfRendering(
        rf -> rPdfF
      )

      val result = Print[IO](store, textRenderer, pdfRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "fail when rendering the fragment fails" in forAll {
    (rf: RenderedFragment, rPdfF: RenderedPdfFragment, rPdfFUri: Uri, event: OrchestratedPrintV2) =>
      val store = MockedStore(rPdfF -> rPdfFUri)
      val textRenderer = MockedTextRenderer.failing(new Exception("Oh no"))
      val pdfRenderer = MockedPdfRendering(
        rf -> rPdfF
      )
      val result = Print[IO](store, textRenderer, pdfRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "set the correct eventId" in forAll {
    (rf: RenderedFragment, rPdfF: RenderedPdfFragment, rPdfFUri: Uri, event: OrchestratedPrintV2) =>
      val store = MockedStore(rPdfF -> rPdfFUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Print.Body) -> rf
      )
      val pdfRenderer = MockedPdfRendering(
        rf -> rPdfF
      )

      val result = Print[IO](store, textRenderer, pdfRenderer, time)(event).futureValue

      result.metadata.eventId shouldBe s"${event.metadata.commId}-composed-print"
  }

  it should "return the correct content URI" in forAll {
    (rf: RenderedFragment, rPdfF: RenderedPdfFragment, rPdfFUri: Uri, event: OrchestratedPrintV2) =>
      val store = MockedStore(rPdfF -> rPdfFUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Print.Body) -> rf
      )
      val pdfRenderer = MockedPdfRendering(
        rf -> rPdfF
      )

      val result = Print[IO](store, textRenderer, pdfRenderer, time)(event).futureValue
      result.pdfIdentifier shouldBe rPdfFUri.renderString
  }

  it should "fill the recipient data correctly" in forAll { event: OrchestratedPrintV2 =>
    // TODO the address entry is here to keep compatibility with old templates
    Print.printRecipientData(event) shouldBe Map(
      "address" -> TemplateData.fromMap(Map(
        "line1" -> TemplateData.fromString(event.address.line1).some,
        "town" -> TemplateData.fromString(event.address.town).some,
        "postcode" -> TemplateData.fromString(event.address.postcode).some,
        "line2" -> event.address.line2.map(TemplateData.fromString),
        "county" -> event.address.county.map(TemplateData.fromString),
        "country" -> event.address.country.map(TemplateData.fromString),
      ).collect { case (k, Some(v)) => k -> v }),
      "recipient" -> TemplateData.fromMap(
        Map(
          "postalAddress" -> TemplateData.fromMap(Map(
            "line1" -> TemplateData.fromString(event.address.line1).some,
            "town" -> TemplateData.fromString(event.address.town).some,
            "postcode" -> TemplateData.fromString(event.address.postcode).some,
            "line2" -> event.address.line2.map(TemplateData.fromString),
            "county" -> event.address.county.map(TemplateData.fromString),
            "country" -> event.address.country.map(TemplateData.fromString),
          ).collect { case (k, Some(v)) => k -> v })
        ))
    )
  }

}
