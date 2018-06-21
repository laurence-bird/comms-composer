package com.ovoenergy.comms.composer.rendering.templating

import java.time.{Clock, OffsetDateTime, ZoneId}

import cats.Id
import com.ovoenergy.comms.composer.TestGenerators
import com.ovoenergy.comms.composer.print.RenderedPrintHtml
import com.ovoenergy.comms.composer.rendering.FailedToRender
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.util.Hash
import org.scalatest.{EitherValues, FlatSpec, Matchers}
import shapeless.Coproduct

// Magic
import org.scalacheck.Shapeless._

class PrintTemplateRenderingSpec extends FlatSpec with Matchers with EitherValues with TestGenerators {

  behavior of "rendering an print"

  val profile = CustomerProfile("Joe", "Bloggs")
  val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())
  val printTemplate = PrintTemplate[Id](HandlebarsTemplate("You paid", requiredFields))
  val address = CustomerAddress(line1 = "10 Oxford Street",
                                line2 = None,
                                town = "London",
                                country = Some("UK"),
                                county = Some("London"),
                                postcode = "W1 1AB")

  def metadataWithCommManifest(templateManifest: TemplateManifest) =
    generate[MetadataV3].copy(templateManifest = templateManifest)

  it should "render a simple template with header and footer" in {
    val manifest = TemplateManifest(Hash("simple"), "0.1")
    val template = PrintTemplate[Id](HandlebarsTemplate("You paid £{{amount}}", requiredFields))
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val printTemplateData =
      PrintTemplateData(customerAddress = address, customerProfile = Some(profile), templateData = data)
    val resultEither =
      PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData,
                                        manifest,
                                        template,
                                        Clock.systemDefaultZone())
    resultEither.right.value.htmlBody should be("You paid £1.23")
  }

  it should "render a simple template without a profile" in {
    val manifest = TemplateManifest(Hash("simple"), "0.1")
    val template = PrintTemplate[Id](HandlebarsTemplate("You paid £{{amount}}", requiredFields))
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val orchestratedPrintEvent = generate[OrchestratedPrintV2].copy(address = address,
                                                                    customerProfile = None,
                                                                    metadata = metadataWithCommManifest(manifest),
                                                                    templateData = data)

    val printTemplateData = PrintTemplateData(data, None, address)

    val resultEither =
      PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData,
                                        manifest,
                                        template,
                                        Clock.systemDefaultZone())
    resultEither.right.value.htmlBody should be("You paid £1.23")
  }

  it should "fail to render an invalid HandlebarsTemplate template" in {
    val manifest = TemplateManifest(Hash("broken"), "0.1")
    val template = PrintTemplate[Id](HandlebarsTemplate("hey check this out {{", requiredFields))
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))) //copied

    val printTemplateData = PrintTemplateData(data, Some(profile), address)

    val result = PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData,
                                                   manifest,
                                                   template,
                                                   Clock.systemDefaultZone())
    result shouldBe 'left
  }

  it should "render a template that references fields in the customer profile" in {
    val manifest = TemplateManifest(Hash("profile-fields"), "0.1")
    val template = PrintTemplate[Id](HandlebarsTemplate("HTML BODY {{profile.firstName}} {{amount}}", requiredFields))
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val printTemplateData = PrintTemplateData(data, Some(profile), address)

    val resultEither =
      PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData,
                                        manifest,
                                        template,
                                        Clock.systemDefaultZone())

    resultEither shouldBe Right(
      RenderedPrintHtml(
        htmlBody = "HTML BODY Joe 1.23"
      ))
  }

  it should "make the recipient's postal address available to the print template as 'address.line1'" in {
    val manifest = TemplateManifest(Hash("recipient-address"), "0.1")
    val template = PrintTemplate[Id](HandlebarsTemplate("HTML BODY {{address.line1}}", requiredFields))

    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val printTemplateData =
      PrintTemplateData(templateData = data, customerProfile = Some(profile), customerAddress = address)

    val resultEither =
      PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData,
                                        manifest,
                                        template,
                                        Clock.systemDefaultZone())
    resultEither.right.value.htmlBody should be("HTML BODY 10 Oxford Street")
  }

  it should "fail if the template references non-existent data" in {
    val manifest = TemplateManifest(Hash("missing-data"), "0.1")
    val template = PrintTemplate[Id](
      HandlebarsTemplate(
        "Hi {{profile.prefix}} {{profile.lastName}}. You bought a {{thing}}. The amount was £{{amount}}.",
        requiredFields))
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val printTemplateData =
      PrintTemplateData(customerAddress = address, customerProfile = Some(profile), templateData = data)

    val renderingErrors =
      PrintTemplateRendering
        .renderHtml(printTemplateData.buildHandlebarsData, manifest, template, Clock.systemDefaultZone())
        .left
        .value

    renderingErrors.reason should include("profile.prefix")
    renderingErrors.reason should include("thing")
    "thing".r.findAllMatchIn(renderingErrors.reason) should have size 1
  }

  it should "render a template that references fields in the system data" in {
    val manifest = TemplateManifest(Hash("system-data-fields"), "0.1")
    val template = PrintTemplate[Id](
      HandlebarsTemplate("HTML BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                         requiredFields))
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val clock = Clock.fixed(OffsetDateTime.parse("2015-12-31T01:23:00Z").toInstant, ZoneId.of("Europe/London"))

    val printTemplateData =
      PrintTemplateData(customerAddress = address, customerProfile = Some(profile), templateData = data)

    val renderingEither =
      PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData, manifest, template, clock)

    renderingEither.right.value.htmlBody should be("HTML BODY 31/12/2015 1.23")
  }

  it should "render template with each and embedded if using this type reference" in {
    val manifest = TemplateManifest(Hash("simple"), "0.1")
    val template = PrintTemplate[Id](
      HandlebarsTemplate(
        "Thanks for your payments of " +
          "{{#each amounts}}" +
          "{{#if this.transaction}}" +
          "{{currency}}{{this.amount}} (transactionId: {{this.transaction.id}}) " +
          "{{else}}" +
          "{{currency}}{{this.amount}} " +
          "{{/if}}" +
          "{{/each}} " +
          "You paid",
        requiredFields
      ))

    //Create
    val templateData = {
      Map(
        "currency" -> TemplateData(Coproduct[TemplateData.TD]("£")),
        "amounts" -> TemplateData(
          Coproduct[TemplateData.TD](Seq(
            TemplateData(Coproduct[TemplateData.TD](Map(
              "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")),
              "transaction" -> TemplateData(
                Coproduct[TemplateData.TD](Map("id" -> TemplateData(Coproduct[TemplateData.TD]("5453ffsdfsdf")))))
            ))),
            TemplateData(Coproduct[TemplateData.TD](Map(
              "amount" -> TemplateData(Coproduct[TemplateData.TD]("100.23"))
            )))
          )))
      )
    }

    val printTemplateData =
      PrintTemplateData(customerAddress = address, customerProfile = Some(profile), templateData = templateData)

    val result = PrintTemplateRendering.renderHtml(printTemplateData.buildHandlebarsData,
                                                   manifest,
                                                   template,
                                                   Clock.systemDefaultZone())

    result.right.value.htmlBody should be(
      "Thanks for your payments of £1.23 (transactionId: 5453ffsdfsdf) £100.23  You paid")

  }
}
