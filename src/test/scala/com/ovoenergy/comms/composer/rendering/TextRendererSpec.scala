package com.ovoenergy.comms.composer
package rendering

import scala.concurrent.ExecutionContext

import cats.Applicative
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._

import com.ovoenergy.comms.model.TemplateData

import model._

import Arbitraries._

class TextRendererSpec extends UnitSpec {

  object MockTemplates {
    val empty: MockTemplates = MockTemplates(Map.empty)
  }

  case class MockTemplates(items: Map[TemplateFragmentId, TemplateFragment]) extends Templates[IO] {

    private val noOfCallsRef: Ref[IO, Int] = Ref[IO].of(0).unsafeRunSync()

    def loadTemplateFragment(id: TemplateFragmentId): IO[Option[TemplateFragment]] = {
      items.get(id).pure[IO].flatTap(_ => noOfCallsRef.update(_ + 1))
    }

    def withTemplate(id: TemplateFragmentId, fragment: TemplateFragment) =
      copy(items = items + (id -> fragment))

    def noOfCalls: IO[Int] = noOfCallsRef.get
  }

  behavior of "TextRenderer"

  it should "return None when the template fragment does not exist" in forAll {
    (id: TemplateFragmentId, data: TemplateData) =>
      TextRenderer[IO](MockTemplates.empty).flatMap(_.render(id, data)).futureValue shouldBe None
  }

  it should "be able to fill up simple template" in forAll { id: TemplateFragmentId =>
    TextRenderer[IO](MockTemplates.empty.withTemplate(id, TemplateFragment("{{this}}")))
      .flatMap(_.render(id, TemplateData.fromString("Foo")))
      .futureValue shouldBe Some(RenderedFragment("Foo"))
  }

  it should "Fail if some keys are missing" in forAll { id: TemplateFragmentId =>
    TextRenderer[IO](MockTemplates.empty.withTemplate(id, TemplateFragment("{{name}},{{surname}}")))
      .flatMap(_.render(id, TemplateData.fromMap(Map("name" -> TemplateData.fromString("Tom")))))
      .attempt
      .futureValue shouldBe a[Left[_, _]]
  }

  it should "Cache templates" in forAll { id: TemplateFragmentId =>
    val mockTemplates = MockTemplates.empty.withTemplate(id, TemplateFragment("{{this}}"))
    val templateData = TemplateData.fromString("Foo")

    TextRenderer[IO](mockTemplates)
      .flatTap(_.render(id, templateData))
      .flatTap(_.render(id, templateData))
      .flatTap(_.render(id, templateData))
      .flatTap(_.render(id, templateData))
      .flatTap(_.render(id, templateData))
      .flatMap(_ => mockTemplates.noOfCalls)
      .futureValue shouldBe 1
  }

}
