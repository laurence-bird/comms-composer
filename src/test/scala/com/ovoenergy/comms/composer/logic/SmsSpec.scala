package com.ovoenergy.comms.composer
package logic

import java.{util => ju}

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.implicits._

import org.http4s.Uri

import org.scalacheck._

import com.ovoenergy.comms.model.sms.OrchestratedSMSV3
import com.ovoenergy.comms.model.TemplateData

import model._
import rendering._
import mocks._

import Arbitraries._

class SmsSpec extends UnitSpec {

  val time = Time[IO]

  behavior of "Sms"

  it should "fail when the body template fragment does not exist" in forAll {
    (rf: RenderedFragment, uri: Uri, event: OrchestratedSMSV3) =>
      val store = MockedStore(rf -> uri)
      val textRenderer = MockedTextRenderer.empty

      val result = Sms[IO](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "succeeded when the body template fragment exists" in forAll {
    (rf: RenderedFragment, uri: Uri, event: OrchestratedSMSV3) =>
      val store = MockedStore(rf -> uri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Sms.Body) -> rf
      )

      val result = Sms[IO](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Right[_, _]]
  }

  it should "set the correct eventId" in forAll {
    (rf: RenderedFragment, uri: Uri, event: OrchestratedSMSV3) =>
      val store = MockedStore(rf -> uri)

      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Sms.Body) -> rf
      )

      val result = Sms[IO](store, textRenderer, time)(event).attempt.futureValue

      result.right.value.metadata.eventId shouldBe s"${event.metadata.commId}-composed-sms"
  }

  it should "fail when storing the fragment fails" in forAll {
    (rf: RenderedFragment, event: OrchestratedSMSV3) =>
      val store = MockedStore.failing(new Exception("Oh no"))
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Sms.Body) -> rf
      )

      val result = Sms[IO](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "fail when rendering the fragment fails" in forAll {
    (rf: RenderedFragment, uri: Uri, event: OrchestratedSMSV3) =>
      val store = MockedStore(rf -> uri)
      val textRenderer = MockedTextRenderer.failing(new Exception("Oh no"))

      val result = Sms[IO](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "return the correct content URI" in forAll {
    (rf: RenderedFragment, uri: Uri, event: OrchestratedSMSV3) =>
      val store = MockedStore(rf -> uri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Sms.Body) -> rf
      )

      val result = Sms[IO](store, textRenderer, time)(event).futureValue
      result.textBody shouldBe uri.renderString
  }

  it should "fill recipient data correclty" in forAll { (event: OrchestratedSMSV3) =>
    Sms.smsRecipientData(event) shouldBe Map(
      "recipient" -> TemplateData.fromMap(
        Map(
          "phoneNumber" -> TemplateData.fromString(event.recipientPhoneNumber)
        ))
    )
  }
}
