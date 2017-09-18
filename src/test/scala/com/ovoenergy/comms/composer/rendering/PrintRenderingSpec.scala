package com.ovoenergy.comms.composer.rendering

import java.time.{Clock, OffsetDateTime, ZoneId}

import cats.Id
import com.ovoenergy.comms.composer.print.RenderedPrintHtml
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest, CustomerAddress, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import org.scalatest.{EitherValues, FlatSpec, Matchers}
import shapeless.Coproduct

class PrintRenderingSpec extends FlatSpec with Matchers with EitherValues {

  behavior of "rendering an print"

  val profile = CustomerProfile("Joe", "Bloggs")
  val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())
  val printTemplate = PrintTemplate[Id](
    header = Some(
      HandlebarsTemplate(
        "Thanks for your payment of " +
          "{{#each payments}}" +
          "{{this.amount}}" +
          "{{else}}" +
          "NA" +
          "{{/each}}",
        requiredFields
      )),
    body = HandlebarsTemplate("You paid", requiredFields),
    footer = Some(HandlebarsTemplate("The amounts were", requiredFields))
  )
  val address = CustomerAddress(line1 = "10 Oxford Street",
                                line2 = "",
                                town = "London",
                                country = "UK",
                                county = "London",
                                postcode = "W1 1AB")

  val renderPrint = PrintRendering.renderHtml(Clock.systemDefaultZone()) _

  it should "render a simple template" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("Thanks for your payment of £{{amount}}", requiredFields)),
      body = HandlebarsTemplate("You paid £{{amount}}", requiredFields),
      footer = Some(HandlebarsTemplate("The amount was £{{amount}}", requiredFields))
    )

    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val result = renderPrint(manifest, template, data, address, Some(profile)).right.value
    result.htmlHeader should be(Some("Thanks for your payment of £1.23"))
    result.htmlBody should be("You paid £1.23")
    result.htmlFooter should be(Some("The amount was £1.23"))
  }

  it should "render a simple template without a profile" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("{{firstName}} thanks for your payment of £{{amount}}", requiredFields)),
      body = HandlebarsTemplate("You paid £{{amount}}", requiredFields),
      footer = Some(HandlebarsTemplate("The amount was £{{amount}}", requiredFields))
    )

    val data = Map(
      "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")),
      "firstName" -> TemplateData(Coproduct[TemplateData.TD]("Jim"))
    )

    val result = renderPrint(manifest, template, data, address, None).right.value
    result.htmlHeader should be(Some("Jim thanks for your payment of £1.23"))
    result.htmlBody should be("You paid £1.23")
    result.htmlFooter should be(Some("The amount was £1.23"))
  }

  it should "fail to render an invalid HandlebarsTemplate template" in {
    val manifest = CommManifest(model.Service, "broken", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("hey check this out {{", requiredFields)),
      body = HandlebarsTemplate("", requiredFields),
      footer = Some(HandlebarsTemplate("", requiredFields))
    )
    renderPrint(manifest, template, Map.empty, address, Some(profile)) should be('left)
  }

  it should "render a template that references fields in the customer profile" in {
    val manifest = CommManifest(model.Service, "profile-fields", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("HEADER {{profile.firstName}} {{amount}}", requiredFields)),
      body = HandlebarsTemplate("HTML BODY {{profile.firstName}} {{amount}}", requiredFields),
      footer = Some(HandlebarsTemplate("FOOTER {{profile.firstName}} {{amount}}", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val resultEither = renderPrint(manifest, template, data, address, Some(profile))

    resultEither shouldBe Right(
      RenderedPrintHtml(
        htmlHeader = Some("HEADER Joe 1.23"),
        htmlBody = "HTML BODY Joe 1.23",
        htmlFooter = Some("FOOTER Joe 1.23")
      ))
  }

  it should "make the recipient's postal address available to the print template as 'address.line1'" in {
    val manifest = CommManifest(model.Service, "recipient-address", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("HEADER", requiredFields)),
      body = HandlebarsTemplate("HTML BODY {{address.line1}}", requiredFields),
      footer = None
    )

    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = renderPrint(manifest, template, data, address, Some(profile)).right.value
    result.htmlBody should be("HTML BODY 10 Oxford Street")
  }

  it should "fail if the template references non-existent data" in {
    val manifest = CommManifest(model.Service, "missing-data", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("Hi {{profile.prefix}} {{profile.lastName}}", requiredFields)),
      body = HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields),
      footer = Some(HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val renderingErrors = renderPrint(manifest, template, data, address, Some(profile)).left.value
    renderingErrors.reason should include("profile.prefix")
    renderingErrors.reason should include("thing")
    "thing".r.findAllMatchIn(renderingErrors.reason) should have size 1
  }

  it should "fail if the template references non-existent data, even if the previous rendering of that template succeeded" in {
    val manifest = CommManifest(model.Service, "missing-data-2", "0.1")
    val template = PrintTemplate[Id](
      header = Some(HandlebarsTemplate("Hi {{profile.firstName}}", requiredFields)),
      body = HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields),
      footer = Some(HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields))
    )
    val validData = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")),
                        "thing" -> TemplateData(Coproduct[TemplateData.TD]("widget")))
    renderPrint(manifest, template, validData, address, Some(profile)) should be('right)
    val invalidData = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val errorMessage = renderPrint(manifest, template, invalidData, address, Some(profile)).left.value
    errorMessage.reason should include("thing")
  }

  it should "render a template that references fields in the system data" in {
    val manifest = CommManifest(model.Service, "system-data-fields", "0.1")
    val template = PrintTemplate[Id](
      header = Some(
        HandlebarsTemplate("HEADER {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                           requiredFields)),
      body = HandlebarsTemplate("HTML BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                                requiredFields),
      footer = Some(
        HandlebarsTemplate("FOOTER {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val clock = Clock.fixed(OffsetDateTime.parse("2015-12-31T01:23:00Z").toInstant, ZoneId.of("Europe/London"))

    val result = PrintRendering.renderHtml(clock)(manifest, template, data, address, Some(profile)).right.value
    result.htmlHeader should be(Some("HEADER 31/12/2015 1.23"))
    result.htmlBody should be("HTML BODY 31/12/2015 1.23")
    result.htmlFooter should be(Some("FOOTER 31/12/2015 1.23"))
  }

  it should "render template with each and embedded if using this type reference" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val template = PrintTemplate[Id](
      header = Some(
        HandlebarsTemplate(
          "Thanks for your payments of " +
            "{{#each amounts}}" +
            "{{#if this.transaction}}" +
            "{{currency}}{{this.amount}} (transactionId: {{this.transaction.id}}) " +
            "{{else}}" +
            "{{currency}}{{this.amount}} " +
            "{{/if}}" +
            "{{/each}}",
          requiredFields
        )),
      body = HandlebarsTemplate("You paid", requiredFields),
      footer = Some(HandlebarsTemplate("The amounts were", requiredFields))
    )

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

    val result: Either[FailedToRender, RenderedPrintHtml] =
      renderPrint(manifest, template, templateData, address, Some(profile))
    if (result.isLeft) fail(result.left.value.reason)
    else {
      result.right.value.htmlHeader should be(
        Some("Thanks for your payments of £1.23 (transactionId: 5453ffsdfsdf) £100.23 "))
      result.right.value.htmlBody should be("You paid")
      result.right.value.htmlFooter should be(Some("The amounts were"))
    }
  }
}
