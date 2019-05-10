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
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen._

import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.model.TemplateData

import model._
import rendering._
import mocks._

import Arbitraries._

class EmailSpec extends UnitSpec {

  case class EmailFragments(
      subjectFrag: RenderedFragment,
      htmlBodyFrag: RenderedFragment,
      textBodyFrag: RenderedFragment,
      subjectUri: Uri,
      htmlBodyUri: Uri,
      textBodyUri: Uri,
  )

  implicit val arbEmailFragments: Arbitrary[EmailFragments] = Arbitrary(
    for {
      subjectFrag <- arbitrary[RenderedFragment]
      htmlBodyFrag <- arbitrary[RenderedFragment]
      textBodyFrag <- arbitrary[RenderedFragment]
      subjectUri <- Gen.const(Uri.uri("/email/subject"))
      htmlBodyUri <- Gen.const(Uri.uri("/email/htmlBody"))
      textBodyUri <- Gen.const(Uri.uri("/email/textBody"))
    } yield
      EmailFragments(
        subjectFrag = subjectFrag,
        htmlBodyFrag = htmlBodyFrag,
        textBodyFrag = textBodyFrag,
        subjectUri = subjectUri,
        htmlBodyUri = htmlBodyUri,
        textBodyUri = textBodyUri
      )
  )

  val time = Time[IO]

  behavior of "Email"

  it should "succeeded when all the required template fragments exist" in forAll {
    (
        subjectFrag: RenderedFragment,
        htmlBodyFrag: RenderedFragment,
        subjectUri: Uri,
        htmlBodyUri: Uri,
        event: OrchestratedEmailV4) =>
      val store = MockedStore(subjectFrag -> subjectUri, htmlBodyFrag -> htmlBodyUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.Subject) -> subjectFrag,
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.HtmlBody) -> htmlBodyFrag,
      )

      val result = Email[IO, IO.Par](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Right[_, _]]
  }

  it should "fail when if the subject template fragments does not exist" in forAll {
    (htmlBodyFrag: RenderedFragment, htmlBodyUri: Uri, event: OrchestratedEmailV4) =>
      val store = MockedStore(htmlBodyFrag -> htmlBodyUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.HtmlBody) -> htmlBodyFrag,
      )

      val result = Email[IO, IO.Par](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "fail when if the htmlBody template fragments does not exist" in forAll {
    (subjectFrag: RenderedFragment, subjectUri: Uri, event: OrchestratedEmailV4) =>
      val store = MockedStore(subjectFrag -> subjectUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.Subject) -> subjectFrag,
      )

      val result = Email[IO, IO.Par](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "set the correct eventId" in forAll {
    (
        subjectFrag: RenderedFragment,
        htmlBodyFrag: RenderedFragment,
        subjectUri: Uri,
        htmlBodyUri: Uri,
        event: OrchestratedEmailV4) =>
      val store = MockedStore(subjectFrag -> subjectUri, htmlBodyFrag -> htmlBodyUri)
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.Subject) -> subjectFrag,
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.HtmlBody) -> htmlBodyFrag,
      )

      val result = Email[IO, IO.Par](store, textRenderer, time)(event).futureValue

      result.metadata.eventId shouldBe s"${event.metadata.commId}-composed-email"
  }

  it should "fail when storing the fragment fails" in forAll {
    (
        subjectFrag: RenderedFragment,
        htmlBodyFrag: RenderedFragment,
        subjectUri: Uri,
        htmlBodyUri: Uri,
        event: OrchestratedEmailV4) =>
      val store = MockedStore.failing(new Exception("oh no"))
      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.Subject) -> subjectFrag,
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.HtmlBody) -> htmlBodyFrag,
      )

      val result = Email[IO, IO.Par](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "fail when rendering the fragment fails" in forAll {
    (
        subjectFrag: RenderedFragment,
        htmlBodyFrag: RenderedFragment,
        subjectUri: Uri,
        htmlBodyUri: Uri,
        event: OrchestratedEmailV4) =>
      val store = MockedStore(subjectFrag -> subjectUri, htmlBodyFrag -> htmlBodyUri)
      val textRenderer = MockedTextRenderer.failing(new Exception("oh no"))
      val result = Email[IO, IO.Par](store, textRenderer, time)(event).attempt.futureValue

      result shouldBe a[Left[_, _]]
  }

  it should "set the email content correctly" in forAll {
    (fragments: EmailFragments, event: OrchestratedEmailV4) =>
      val senderFrag = RenderedFragment("OVO Energy <hello@ovoenergy.com>")
      val store = MockedStore(
        fragments.subjectFrag -> fragments.subjectUri,
        fragments.htmlBodyFrag -> fragments.htmlBodyUri,
        fragments.textBodyFrag -> fragments.textBodyUri,
      )

      val textRenderer = MockedTextRenderer(
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.Sender) -> senderFrag,
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.Subject) -> fragments.subjectFrag,
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.HtmlBody) -> fragments.htmlBodyFrag,
        templateFragmentIdFor(event.metadata.templateManifest, TemplateFragmentType.Email.TextBody) -> fragments.textBodyFrag,
      )

      val result = Email[IO, IO.Par](store, textRenderer, time)(event).futureValue

      result.htmlBody shouldBe fragments.htmlBodyUri.renderString
      result.textBody shouldBe Some(fragments.textBodyUri.renderString)
      result.subject shouldBe fragments.subjectUri.renderString
      result.sender shouldBe senderFrag.value
  }

  it should "fill recipient data correclty" in forAll { (event: OrchestratedEmailV4) =>
    Email.emailRecipientData(event) shouldBe Map(
      "recipient" -> TemplateData.fromMap(
        Map(
          "emailAddress" -> TemplateData.fromString(event.recipientEmailAddress)
        ))
    )
  }

}
