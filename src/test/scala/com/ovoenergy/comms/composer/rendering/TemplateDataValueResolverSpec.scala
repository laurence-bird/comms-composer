package com.ovoenergy.comms.composer
package rendering

import scala.concurrent.ExecutionContext

import cats.Id
import cats.data.Validated.Valid
import cats.effect.{IO, Effect}

import scala.collection.JavaConverters._

import com.github.jknack.handlebars
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.StringTemplateSource
import com.github.jknack.handlebars.ValueResolver

import org.scalacheck._

import com.ovoenergy.comms.model.{SMS, Print, Email, TemplateManifest, TemplateData}
import com.ovoenergy.comms.templates
import templates.retriever.{PartialsRetriever, TemplatesRetriever}
import templates.{ErrorsOr, TemplatesContext}
import templates.cache.CachingStrategy
import templates.model._
import templates.parsing.handlebars.HandlebarsParsing
import template.files.TemplateFile
import template.files.email.EmailTemplateFiles
import template.files.print.PrintTemplateFiles
import template.files.sms.SMSTemplateFiles
import template.processed.email.EmailTemplate
import template.processed.print.PrintTemplate
import template.processed.sms.SMSTemplate

import model.ComposerError

class TemplateDataValueResolverSpec extends UnitSpec {

  implicit def noShrink[A]: Shrink[A] = Shrink.shrinkAny
  implicit var arbString: Arbitrary[String] = Arbitrary {
    for {
      len <- Gen.choose(0, 12)
      chars <- Gen.listOfN(len, Gen.alphaNumChar)
    } yield chars.mkString
  }

  val resolver = new TemplateDataValueResolver
  val handlebarsEngine = new handlebars.Handlebars()

  "TemplateDataValueResolver" should "resolve string in ." in forAll { value: String =>
    val template = handlebarsEngine.compileInline("{{.}}")
    val data = TemplateData.fromString(value)
    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe value
  }

  "TemplateDataValueResolver" should "resolve string in this" in forAll { value: String =>
    val template = handlebarsEngine.compileInline("{{this}}")
    val data = TemplateData.fromString(value)
    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe value
  }

  "TemplateDataValueResolver" should "resolve list of strings" in forAll { values: List[String] =>
    val template = handlebarsEngine.compileInline("{{#each this}}{{this}}{{/each}}")
    val data = TemplateData.fromSeq(values.map(TemplateData.fromString))
    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe values.mkString
  }

  "TemplateDataValueResolver" should "resolve list of maps" in forAll {
    values: List[(String, String)] =>
      val template = handlebarsEngine.compileInline("{{#each this}}{{name}},{{surname}}{{/each}}")
      val data = TemplateData.fromSeq(values.map {
        case (name, surname) =>
          TemplateData.fromMap(
            Map(
              "name" -> TemplateData.fromString(name),
              "surname" -> TemplateData.fromString(surname)))
      })
      val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

      template(ctx) shouldBe values.map { case (k, v) => s"$k,$v" }.mkString
  }

  "TemplateDataValueResolver" should "resolve list of list" in forAll { values: List[String] =>
    val template =
      handlebarsEngine.compileInline("{{#each this}}{{#each this}}{{this}}{{/each}}{{/each}}")
    val data = TemplateData.fromSeq(
      List.fill(3)(TemplateData.fromSeq(values.map(TemplateData.fromString)))
    )

    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe List.fill(3)(values).flatten.mkString

  }

  "TemplateDataValueResolver" should "resolve map of string" in forAll { value: String =>
    val template = handlebarsEngine.compileInline("{{name}}")
    val data = TemplateData.fromMap(
      Map(
        "name" -> TemplateData.fromString(value)
      ))
    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe value
  }

  "TemplateDataValueResolver" should "resolve map of list" in forAll { values: List[String] =>
    val template = handlebarsEngine.compileInline("{{#each xs}}{{this}}{{/each}}")
    val data = TemplateData.fromMap(
      Map(
        "xs" -> TemplateData.fromSeq(values.map(TemplateData.fromString))
      ))
    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe values.mkString
  }

  "TemplateDataValueResolver" should "resolve map of map" in {
    val template = handlebarsEngine.compileInline("{{tom.name}}")
    val data = TemplateData.fromMap(
      Map(
        "tom" -> TemplateData.fromMap(
          Map(
            "name" -> TemplateData.fromString("Tom")
          )
        )
      )
    )
    val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

    template(ctx) shouldBe "Tom"
  }

}
