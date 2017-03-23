package com.ovoenergy.comms.rendering

import java.time.{Clock, OffsetDateTime, ZoneId}

import cats.Id
import cats.data.Validated.Valid
import com.ovoenergy.comms.model.{TemplateData, _}
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import org.scalatest._
import shapeless.Coproduct

class SMSRenderingSpec extends FlatSpec with Matchers with EitherValues {

  behavior of "rendering an SMS"

  val profile = CustomerProfile("Joe", "Bloggs")
  val phoneNumber = "+447123123456"
  val requiredFields = Valid(RequiredTemplateData.obj(Map[String, RequiredTemplateData]()))

  val renderSMS = Rendering.renderSMS(Clock.systemDefaultZone()) _

  it should "render a simple template" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val template = SMSTemplate[Id](
      textBody = HandlebarsTemplate("You paid £{{amount}}", requiredFields)
    )

    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = renderSMS(manifest, template, data, profile, phoneNumber).right.value
    result.textBody should be("You paid £1.23")
  }

  it should "render a template that references fields in the customer profile" in {
    val manifest = CommManifest(CommType.Service, "profile-fields", "0.1")
    val template = SMSTemplate[Id](
      textBody = HandlebarsTemplate("TEXT BODY {{profile.firstName}} {{amount}}", requiredFields)
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = renderSMS(manifest, template, data, profile, phoneNumber).right.value
    result.textBody should be("TEXT BODY Joe 1.23")
  }

  it should "make the recipient phone number available to the SMS template as 'recipient.phoneNumber'" in {
    val manifest = CommManifest(CommType.Service, "recipient-phone-number", "0.1")
    val template = SMSTemplate[Id](
      textBody = HandlebarsTemplate("TEXT BODY {{recipient.phoneNumber}}", requiredFields)
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val result = renderSMS(manifest, template, data, profile, phoneNumber).right.value
    result.textBody should be("TEXT BODY +447123123456")
  }

  it should "fail if the template references non-existent data" in {
    val manifest = CommManifest(CommType.Service, "missing-data", "0.1")
    val template = SMSTemplate[Id](
      textBody = HandlebarsTemplate("Hi {{profile.prefix}}. You bought a {{thing}}. The amount was £{{amount}}.",
                                    requiredFields)
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))

    val renderingErrors = renderSMS(manifest, template, data, profile, phoneNumber).left.value
    renderingErrors.reason should include("profile.prefix")
    renderingErrors.reason should include("thing")
  }

  it should "render a template that references fields in the system data" in {
    val manifest = CommManifest(CommType.Service, "system-data-fields", "0.1")
    val template = SMSTemplate[Id](
      textBody = HandlebarsTemplate("TEXT BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}",
                                    requiredFields)
    )
    val data = Map("amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23")))
    val clock = Clock.fixed(OffsetDateTime.parse("2015-12-31T01:23:00Z").toInstant, ZoneId.of("Europe/London"))

    val result = Rendering.renderSMS(clock)(manifest, template, data, profile, phoneNumber).right.value
    result.textBody should be("TEXT BODY 31/12/2015 1.23")
  }

}
