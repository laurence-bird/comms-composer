package com.ovoenergy.comms.composer.rendering.templating

import java.time.{Clock, OffsetDateTime, ZoneId}

import cats.Id
import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.composer.rendering.FailedToRender
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{TemplateData, _}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import org.scalatest._
import shapeless.Coproduct

class EmailTemplateRenderingSpec extends FlatSpec with Matchers with EitherValues {

  behavior of "rendering an email"

  val profile = CustomerProfile("Joe", "Bloggs")
  val emailAddress = "joe.bloggs@ovoenergy.com"
  val requiredFields = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())
  val emailTemplate = EmailTemplate[Id](
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

  it should "render a simple template" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("Thanks for your payment of £{{amount}}", requiredFields),
      htmlBody = HandlebarsTemplate("You paid £{{amount}}", requiredFields),
      textBody = Some(HandlebarsTemplate("The amount was £{{amount}}", requiredFields))
    )

    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(), manifest, template, EmailTemplateData(data, Some(profile), emailAddress))
      .right
      .value
    result.subject should be("Thanks for your payment of £1.23")
    result.htmlBody should be("You paid £1.23")
    result.textBody should be(Some("The amount was £1.23"))
  }

  it should "render a simple template without a profile" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("{{firstName}} thanks for your payment of £{{amount}}", requiredFields),
      htmlBody = HandlebarsTemplate("You paid £{{amount}}", requiredFields),
      textBody = Some(HandlebarsTemplate("The amount was £{{amount}}", requiredFields))
    )

    val data = Map(
      "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")),
      "firstName" -> TemplateData(Coproduct[TemplateData.TD]("Jim"))
    )

    val result = EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(), manifest, template, EmailTemplateData(data, None, emailAddress))
      .right
      .value
    result.subject should be("Jim thanks for your payment of £1.23")
    result.htmlBody should be("You paid £1.23")
    result.textBody should be(Some("The amount was £1.23"))
  }

  it should "fail to render an invalid HandlebarsTemplate template" in {
    val manifest = CommManifest(model.Service, "broken", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("hey check this out {{", requiredFields),
      htmlBody = HandlebarsTemplate("", requiredFields),
      textBody = Some(HandlebarsTemplate("", requiredFields))
    )
    EmailTemplateRendering.renderEmail(Clock.systemDefaultZone(),
                                       manifest,
                                       template,
                                       EmailTemplateData(Map.empty, Some(profile), emailAddress)) should be('left)
  }

  it should "render a template that references fields in the customer profile" in {
    val manifest = CommManifest(model.Service, "profile-fields", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("SUBJECT {{profile.firstName}} {{amount}}", requiredFields),
      htmlBody = HandlebarsTemplate("HTML BODY {{profile.firstName}} {{amount}}", requiredFields),
      textBody = Some(HandlebarsTemplate("TEXT BODY {{profile.firstName}} {{amount}}", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val resultEither: Either[FailedToRender, RenderedEmail] =
      EmailTemplateRendering.renderEmail(Clock.systemDefaultZone(),
                                         manifest,
                                         template,
                                         EmailTemplateData(data, Some(profile), emailAddress))
    resultEither shouldBe Right(
      RenderedEmail(
        "SUBJECT Joe 1.23",
        "HTML BODY Joe 1.23",
        Some("TEXT BODY Joe 1.23")
      ))
  }

  it should "fail to render a template that references fields in the customer profile where no profile provided" in {
    val manifest = CommManifest(model.Service, "profile-fields", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("SUBJECT {{profile.firstName}} {{amount}}", requiredFields),
      htmlBody = HandlebarsTemplate("HTML BODY {{profile.firstName}} {{amount}}", requiredFields),
      textBody = Some(HandlebarsTemplate("TEXT BODY {{profile.firstName}} {{amount}}", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val renderingErrors = EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(), manifest, template, EmailTemplateData(data, None, emailAddress))
      .left
      .value
    renderingErrors.reason should include("profile.firstName")
  }

  it should "make the recipient email address available to the email template as 'recipient.emailAddress'" in {
    val manifest = CommManifest(model.Service, "recipient-email-address", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("SUBJECT", requiredFields),
      htmlBody = HandlebarsTemplate("HTML BODY {{recipient.emailAddress}}", requiredFields),
      textBody = None
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result: RenderedEmail = EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(), manifest, template, EmailTemplateData(data, Some(profile), emailAddress))
      .right
      .value
    result.htmlBody should be("HTML BODY joe.bloggs@ovoenergy.com")
  }

  it should "fail if the template references non-existent data" in {
    val manifest = CommManifest(model.Service, "missing-data", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("Hi {{profile.prefix}} {{profile.lastName}}", requiredFields),
      htmlBody = HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields),
      textBody = Some(HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields))
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val renderingErrors = EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(), manifest, template, EmailTemplateData(data, Some(profile), emailAddress))
      .left
      .value
    renderingErrors.reason should include("profile.prefix")
    renderingErrors.reason should include("thing")
    "thing".r.findAllMatchIn(renderingErrors.reason) should have size 1
  }

  it should "fail if the template references non-existent data, even if the previous rendering of that template succeeded" in {
    val manifest = CommManifest(model.Service, "missing-data-2", "0.1")
    val template = EmailTemplate[Id](
      sender = None,
      subject = HandlebarsTemplate("Hi {{profile.firstName}}", requiredFields),
      htmlBody = HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields),
      textBody = Some(HandlebarsTemplate("You bought a {{thing}}. The amount was £{{amount}}.", requiredFields))
    )
    val validData = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")),
                        "thing" -> TemplateData(Coproduct[TemplateData.TD]("widget")))
    EmailTemplateRendering.renderEmail(Clock.systemDefaultZone(),
                                       manifest,
                                       template,
                                       EmailTemplateData(validData, Some(profile), emailAddress)) should be('right)
    val invalidData = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val errorMessage = EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(),
                   manifest,
                   template,
                   EmailTemplateData(invalidData, Some(profile), emailAddress))
      .left
      .value
    errorMessage.reason should include("thing")
  }

  it should "render a template that references fields in the system data" in {
    val manifest = CommManifest(model.Service, "system-data-fields", "0.1")
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

    val result =
      EmailTemplateRendering
        .renderEmail(clock, manifest, template, EmailTemplateData(data, Some(profile), emailAddress))
        .right
        .value
    result.subject should be("SUBJECT 31/12/2015 1.23")
    result.htmlBody should be("HTML BODY 31/12/2015 1.23")
    result.textBody should be(Some("TEXT BODY 31/12/2015 1.23"))
  }

  it should "render template with each and embedded if using this type reference" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
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

    val result: Either[FailedToRender, RenderedEmail] =
      EmailTemplateRendering
        .renderEmail(Clock.systemDefaultZone(),
                     manifest,
                     template,
                     EmailTemplateData(templateData, Some(profile), emailAddress))
    if (result.isLeft) fail(result.left.value.reason)
    else {
      result.right.value.subject should be("Thanks for your payments of £1.23 (transactionId: 5453ffsdfsdf) £100.23 ")
      result.right.value.htmlBody should be("You paid")
      result.right.value.textBody should be(Some("The amounts were"))
    }
  }
  it should "render the else block of an if" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
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

    EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(),
                   manifest,
                   template,
                   EmailTemplateData(templateData, Some(profile), emailAddress))
      .right
      .value
      .subject shouldBe "Thanks for your payment of £0.00"
  }

  it should "render the else block of an each" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val templateData = Map(
      "payments" -> TemplateData(Coproduct[TemplateData.TD](Seq[TemplateData]()))
    )

    EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(),
                   manifest,
                   emailTemplate,
                   EmailTemplateData(templateData, Some(profile), emailAddress))
      .right
      .value
      .subject shouldBe "Thanks for your payment of NA"
  }

  it should "Known issue with missing each parameter" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val templateData = Map[String, TemplateData]()

    EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(),
                   manifest,
                   emailTemplate,
                   EmailTemplateData(templateData, Some(profile), emailAddress))
      .right
      .value
      .subject shouldBe "Thanks for your payment of "
  }
  it should "validate missing field from each context" in {
    val manifest = CommManifest(model.Service, "simple", "0.1")
    val templateData = Map(
      "payments" -> TemplateData(
        Coproduct[TemplateData.TD](
          Seq(
            TemplateData(Coproduct[TemplateData.TD](Map("someKey" -> TemplateData(Coproduct[TemplateData.TD]("HI")))))
          )))
    )

    EmailTemplateRendering
      .renderEmail(Clock.systemDefaultZone(),
                   manifest,
                   emailTemplate,
                   EmailTemplateData(templateData, Some(profile), emailAddress))
      .left
      .value
      .reason should (include("The template referenced the following non-existent keys:") and include("this.amount"))

  }
}
