package com.ovoenergy.comms

import java.time.{Clock, OffsetDateTime, ZoneId}

import cats.Id
import cats.data.Validated.Valid
import com.ovoenergy.comms.Rendering.RenderingErrors
import com.ovoenergy.comms.email.RenderedEmail
import com.ovoenergy.comms.model.TemplateData.TD
import com.ovoenergy.comms.model.{TemplateData, _}
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import org.scalatest._
import org.scalatest.Assertions.fail
import shapeless.Coproduct

class RenderingSpec extends FlatSpec with Matchers with EitherValues {

  val profile = CustomerProfile("Joe", "Bloggs")
  val emailAddress = "joe.bloggs@ovoenergy.com"
  val requiredFields = Valid(RequiredTemplateData.obj(Map[String, RequiredTemplateData]()))
  val eachTemplate = EmailTemplate[Id](
    sender = None,
    subject = HandlebarsTemplate(
      "Thanks for your payment of " +
        "{{#each payments}}" +
        "{{this.amount}}" +
        "{{else}}" +
        "NA" +
        "{{/each}}",
      requiredFields
    ),
    htmlBody = HandlebarsTemplate("You paid", requiredFields),
    textBody = Some(HandlebarsTemplate("The amounts were", requiredFields))
  )

  val render = Rendering.renderEmail(Clock.systemDefaultZone()) _

  it should "render a simple template" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("Thanks for your payment of £{{amount}}", requiredFields),
      htmlBody = HandlebarsTemplate("You paid £{{amount}}", requiredFields),
      textBody = Some(HandlebarsTemplate("The amount was £{{amount}}", requiredFields))
    )

    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = render(manifest, template, data, profile, emailAddress).right.value
    result.subject should be("Thanks for your payment of £1.23")
    result.htmlBody should be("You paid £1.23")
    result.textBody should be(Some("The amount was £1.23"))
  }

  it should "fail to render an invalid HandlebarsTemplate template" in {
    val manifest = CommManifest(CommType.Service, "broken", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("hey check this out {{", requiredFields),
      htmlBody = HandlebarsTemplate("", requiredFields),
      textBody = Some(HandlebarsTemplate("", requiredFields))
    )
    render(manifest, template, Map.empty, profile, emailAddress) should be('left)
  }

  it should "render a template that references fields in the customer profile" in {
    val manifest = CommManifest(CommType.Service, "profile-fields", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("SUBJECT {{profile.firstName}} {{amount}}", requiredFields),
      htmlBody = HandlebarsTemplate("HTML BODY {{profile.firstName}} {{amount}}", requiredFields),
      textBody = Some(HandlebarsTemplate("TEXT BODY {{profile.firstName}} {{amount}}", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = render(manifest, template, data, profile, emailAddress).right.value
    result.subject should be("SUBJECT Joe 1.23")
    result.htmlBody should be("HTML BODY Joe 1.23")
    result.textBody should be(Some("TEXT BODY Joe 1.23"))
  }

  it should "make the recipient email address available to the email template as 'recipient.emailAddress'" in {
    val manifest = CommManifest(CommType.Service, "recipient-email-address", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("SUBJECT", requiredFields),
      htmlBody = HandlebarsTemplate("HTML BODY {{recipient.emailAddress}}", requiredFields),
      textBody = None
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = render(manifest, template, data, profile, emailAddress).right.value
    result.htmlBody should be("HTML BODY joe.bloggs@ovoenergy.com")
  }

  it should "fail if the template references non-existent data" in {
    val manifest = CommManifest(CommType.Service, "missing-data", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("Hi {{profile.prefix}} {{profile.lastName}}", requiredFields),
      htmlBody = HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields),
      textBody = Some(HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val renderingErrors = render(manifest, template, data, profile, emailAddress).left.value
    renderingErrors.reason should include("profile.prefix")
    renderingErrors.reason should include("thing")
    "thing".r.findAllMatchIn(renderingErrors.reason) should have size 1
  }

  it should "fail if the template references non-existent data, even if the previous rendering of that template succeeded" in {
    val manifest = CommManifest(CommType.Service, "missing-data-2", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("Hi {{profile.firstName}}", requiredFields),
      htmlBody = HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields),
      textBody = Some(HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields))
    )
    val validData = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")),
                        "thing" -> TemplateData(Coproduct[TemplateData.TD]("widget")))
    render(manifest, template, validData, profile, emailAddress) should be('right)
    val invalidData = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val errorMessage = render(manifest, template, invalidData, profile, emailAddress).left.value
    errorMessage.reason should include("thing")
  }

  it should "render a template that references fields in the system data" in {
    val manifest = CommManifest(CommType.Service, "system-data-fields", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("SUBJECT {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                                   requiredFields),
      htmlBody = HandlebarsTemplate("HTML BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                                    requiredFields),
      textBody = Some(
        HandlebarsTemplate("TEXT BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                           requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val clock = Clock.fixed(OffsetDateTime.parse("2015-12-31T01:23:00Z").toInstant, ZoneId.of("Europe/London"))

    val result = Rendering.renderEmail(clock)(manifest, template, data, profile, emailAddress).right.value
    result.subject should be("SUBJECT 31/12/2015 1.23")
    result.htmlBody should be("HTML BODY 31/12/2015 1.23")
    result.textBody should be(Some("TEXT BODY 31/12/2015 1.23"))
  }

  it should "render template with each and embedded if using this type reference" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate(
        "Thanks for your payments of " +
          "{{#each amounts}}" +
          "{{#if this.transaction}}" +
          "{{currency}}{{this.amount}} (transactionId: {{this.transaction.id}}) " +
          "{{else}}" +
          "{{currency}}{{this.amount}} " +
          "{{/if}}" +
          "{{/each}}",
        requiredFields
      ),
      htmlBody = HandlebarsTemplate("You paid", requiredFields),
      textBody = Some(HandlebarsTemplate("The amounts were", requiredFields))
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

    val result: Either[RenderingErrors, RenderedEmail] =
      render(manifest, template, templateData, profile, emailAddress)
    if (result.isLeft) fail(result.left.value.reason)
    else {
      result.right.value.subject should be("Thanks for your payments of £1.23 (transactionId: 5453ffsdfsdf) £100.23 ")
      result.right.value.htmlBody should be("You paid")
      result.right.value.textBody should be(Some("The amounts were"))
    }
  }
  it should "render the else block of an if" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate(
        "Thanks for your payment of " +
          "{{#if payment}}" +
          "{{payment.amount}}" +
          "{{else}}" +
          "£0.00" +
          "{{/if}}",
        requiredFields
      ),
      htmlBody = HandlebarsTemplate("You paid", requiredFields),
      textBody = Some(HandlebarsTemplate("The amounts were", requiredFields))
    )
    val templateData = Map.empty[String, TemplateData]

    render(manifest, template, templateData, profile, emailAddress).right.value.subject shouldBe "Thanks for your payment of £0.00"
  }

  it should "render the else block of an each" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val templateData = Map(
      "payments" -> TemplateData(Coproduct[TemplateData.TD](Seq[TemplateData]()))
    )

    render(manifest, eachTemplate, templateData, profile, emailAddress).right.value.subject shouldBe "Thanks for your payment of NA"
  }

  it should "Known issue with missing each parameter" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val templateData = Map[String, TemplateData]()

    render(manifest, eachTemplate, templateData, profile, emailAddress).right.value.subject shouldBe "Thanks for your payment of "
  }
  it should "validate missing field from each context" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val templateData = Map(
      "payments" -> TemplateData(
        Coproduct[TemplateData.TD](
          Seq(
            TemplateData(Coproduct[TemplateData.TD](Map("someKey" -> TemplateData(Coproduct[TemplateData.TD]("HI")))))
          )))
    )

    render(manifest, eachTemplate, templateData, profile, emailAddress).left.value.reason should (include(
      "The template referenced the following non-existent keys:") and include("- this.amount"))

  }
}
