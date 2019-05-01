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

class MissingKeysContextSpec extends UnitSpec {

  implicit def noShrink[A]: Shrink[A] = Shrink.shrinkAny
  implicit var arbString: Arbitrary[String] = Arbitrary {
    for {
      len <- Gen.choose(0, 12)
      chars <- Gen.listOfN(len, Gen.alphaNumChar)
    } yield chars.mkString
  }

  val resolver = new TemplateDataValueResolver

  val handlebarsEngine = new handlebars.Handlebars()
    .registerHelperMissing(MissingKeys.helper[AnyRef])

  "MissingKeys" should "collect missing keys" in forAll { value: String =>
    val template = handlebarsEngine.compileInline("{{#with tom}}{{name}},{{surname}}{{/with}}")
    val data = Map(
      "tom" -> Map("name" -> "Tom").asJava,
      "bob" -> Map("name" -> "Bob", "surname" -> "Tayler").asJava,
    ).asJava
    val ctx = handlebars.Context.newBuilder(data).build()

    val result = template(ctx)

    // It is not great as we don't know the full path, can we???
    MissingKeys.missingKeysFor(ctx) should contain only ("surname")
  }

  "MissingKeys" should "collect all missing keys" in forAll { value: String =>
    val template = handlebarsEngine.compileInline("{{#each this}}{{name}},{{surname}}{{/each}}")
    val data = List(
      Map("name" -> "Tom").asJava,
      Map("name" -> "Bob").asJava,
    ).asJava
    val ctx = handlebars.Context.newBuilder(data).build()

    val result = template(ctx)

    MissingKeys.missingKeysFor(ctx) should contain theSameElementsAs List("surname", "surname")
  }

  "MissingKeys" should "collect missing key with the full name" in forAll { value: String =>
    val template = handlebarsEngine.compileInline("{{tom.name}},{{tom.surname}}")
    val data = Map(
      "tom" -> Map("name" -> "Tom").asJava,
      "bob" -> Map("name" -> "Bob").asJava,
    ).asJava
    val ctx = handlebars.Context.newBuilder(data).build()

    val result = template(ctx)

    // It is not great as we don't know the full path, can we???
    MissingKeys.missingKeysFor(ctx) should contain theSameElementsAs List("tom.surname")
  }

  "MissingKeys" should "collect missing key with the full name using TemlateData" in forAll {
    value: String =>
      val template = handlebarsEngine.compileInline("{{tom.name}},{{tom.surname}}")
      val data = TemplateData.fromMap(
        Map(
          "tom" -> TemplateData.fromMap(Map("name" -> TemplateData.fromString("Tom"))),
          "bob" -> TemplateData.fromMap(Map("name" -> TemplateData.fromString("Bob"))),
        ))
      val ctx = handlebars.Context.newBuilder(data).resolver(resolver).build()

      val result = template(ctx)

      // It is not great as we don't know the full path, can we???
      MissingKeys.missingKeysFor(ctx) should contain theSameElementsAs List("tom.surname")
  }

}
