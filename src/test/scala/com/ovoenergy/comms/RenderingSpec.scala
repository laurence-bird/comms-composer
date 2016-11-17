package com.ovoenergy.comms

import java.time.{Clock, OffsetDateTime, ZoneId}

import org.scalatest._

class RenderingSpec extends FlatSpec with Matchers with EitherValues {

  val profile = CustomerProfile("Joe", "Bloggs")

  val render = Rendering.render(Clock.systemDefaultZone()) _

  it should "render a simple template" in {
    val manifest = CommManifest(CommType.Service, "simple", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("Thanks for your payment of £{{amount}}"),
      htmlBody = Mustache("You paid £{{amount}}"),
      textBody = Some(Mustache("The amount was £{{amount}}")),
      htmlFragments = Map.empty,
      textFragments = Map.empty
    )
    val data = Map("amount" -> "1.23")

    val result = render(manifest, template, data, profile).right.value
    result.subject should be("Thanks for your payment of £1.23")
    result.htmlBody should be("You paid £1.23")
    result.textBody should be(Some("The amount was £1.23"))
  }

  it should "fail to render an invalid Mustache template" in {
    val manifest = CommManifest(CommType.Service, "broken", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("hey check this out {{"),
      htmlBody = Mustache(""),
      textBody = Some(Mustache("")),
      htmlFragments = Map.empty,
      textFragments = Map.empty
    )

    render(manifest, template, Map.empty, profile) should be('left)
  }

  it should "render a template with partials" in {
    val manifest = CommManifest(CommType.Service, "partials", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("Thanks for your payment of £{{amount}}"),
      htmlBody = Mustache("{{> header}} You paid £{{amount}} {{> footer}}"),
      textBody = Some(Mustache("{{> header}} The amount was £{{amount}} {{> footer}}")),
      htmlFragments = Map(
        "header" -> Mustache("HTML HEADER"),
        "footer" -> Mustache("HTML FOOTER")
      ),
      textFragments = Map(
        "header" -> Mustache("TEXT HEADER"),
        "footer" -> Mustache("TEXT FOOTER")
      )
    )
    val data = Map("amount" -> "1.23")

    val result = render(manifest, template, data, profile).right.value
    result.subject should be("Thanks for your payment of £1.23")
    result.htmlBody should be("HTML HEADER You paid £1.23 HTML FOOTER")
    result.textBody should be(Some("TEXT HEADER The amount was £1.23 TEXT FOOTER"))
  }

  it should "render a template that references fields in the customer profile" in {
    val manifest = CommManifest(CommType.Service, "profile-fields", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("SUBJECT {{profile.firstName}} {{amount}}"),
      htmlBody = Mustache("{{> header}} HTML BODY {{profile.firstName}} {{amount}} {{> footer}}"),
      textBody = Some(Mustache("{{> header}} TEXT BODY {{profile.firstName}} {{amount}} {{> footer}}")),
      htmlFragments = Map(
        "header" -> Mustache("HTML HEADER {{profile.firstName}} {{amount}}"),
        "footer" -> Mustache("HTML FOOTER {{profile.firstName}} {{amount}}")
      ),
      textFragments = Map(
        "header" -> Mustache("TEXT HEADER {{profile.firstName}} {{amount}}"),
        "footer" -> Mustache("TEXT FOOTER {{profile.firstName}} {{amount}}")
      )
    )
    val data = Map("amount" -> "1.23")

    val result = render(manifest, template, data, profile).right.value
    result.subject should be("SUBJECT Joe 1.23")
    result.htmlBody should be("HTML HEADER Joe 1.23 HTML BODY Joe 1.23 HTML FOOTER Joe 1.23")
    result.textBody should be(Some("TEXT HEADER Joe 1.23 TEXT BODY Joe 1.23 TEXT FOOTER Joe 1.23"))
  }

  it should "fail if the template references non-existent data" in {
    val manifest = CommManifest(CommType.Service, "missing-data", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("Hi {{profile.prefix}} {{profile.lastName}}"),
      htmlBody = Mustache("You bought a {{thing}}. The amount was £{{amount}}."),
      textBody = Some(Mustache("You bought a {{thing}}. The amount was £{{amount}}.")),
      htmlFragments = Map.empty,
      textFragments = Map.empty
    )
    val data = Map("amount" -> "1.23")

    val errorMessage = render(manifest, template, data, profile).left.value
    errorMessage should include("profile.prefix")
    errorMessage should include("thing")
    "thing".r.findAllMatchIn(errorMessage) should have size 1
  }

  it should "fail if the template references a non-existent partial" in {
    val manifest = CommManifest(CommType.Service, "missing-partials", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("Thanks for your payment of £{{amount}}"),
      htmlBody = Mustache("{{> yolo}} You paid £{{amount}} {{> footer}}"),
      textBody = Some(Mustache("{{> yolo}} The amount was £{{amount}} {{> footer}}")),
      htmlFragments = Map(
        "header" -> Mustache("HTML HEADER"),
        "footer" -> Mustache("HTML FOOTER")
      ),
      textFragments = Map(
        "header" -> Mustache("TEXT HEADER"),
        "footer" -> Mustache("TEXT FOOTER")
      )
    )
    val data = Map("amount" -> "1.23")

    render(manifest, template, data, profile) should be('left)
  }

  it should "render a template that references fields in the system data" in {
    val manifest = CommManifest(CommType.Service, "system-data-fields", "0.1")
    val template = Template(
      sender = None,
      subject = Mustache("SUBJECT {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}"),
      htmlBody = Mustache(
        "{{> header}} HTML BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}} {{> footer}}"),
      textBody = Some(
        Mustache(
          "{{> header}} TEXT BODY {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}} {{> footer}}")),
      htmlFragments = Map(
        "header" -> Mustache("HTML HEADER {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}"),
        "footer" -> Mustache("HTML FOOTER {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}")
      ),
      textFragments = Map(
        "header" -> Mustache("TEXT HEADER {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}"),
        "footer" -> Mustache("TEXT FOOTER {{system.dayOfMonth}}/{{system.month}}/{{system.year}} {{amount}}")
      )
    )
    val data = Map("amount" -> "1.23")
    val clock = Clock.fixed(OffsetDateTime.parse("2015-12-31T01:23:00Z").toInstant, ZoneId.of("Europe/London"))

    val result = Rendering.render(clock)(manifest, template, data, profile).right.value
    result.subject should be("SUBJECT 31/12/2015 1.23")
    result.htmlBody should be("HTML HEADER 31/12/2015 1.23 HTML BODY 31/12/2015 1.23 HTML FOOTER 31/12/2015 1.23")
    result.textBody should be(
      Some("TEXT HEADER 31/12/2015 1.23 TEXT BODY 31/12/2015 1.23 TEXT FOOTER 31/12/2015 1.23"))
  }
}
