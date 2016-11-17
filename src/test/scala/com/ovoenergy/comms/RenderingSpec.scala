package com.ovoenergy.comms

import org.scalatest._

class RenderingSpec extends FlatSpec with Matchers with EitherValues {

  val profile = CustomerProfile("Joe", "Bloggs")

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

    val result = Rendering.render(manifest, template, data, profile).right.value
    result.subject should be("Thanks for your payment of £1.23")
    result.htmlBody should be("You paid £1.23")
    result.textBody should be(Some("The amount was £1.23"))
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

    val result = Rendering.render(manifest, template, data, profile).right.value
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

    val result = Rendering.render(manifest, template, data, profile).right.value
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

    val errorMessage = Rendering.render(manifest, template, data, profile).left.value
    errorMessage should include("profile.prefix")
    errorMessage should include("thing")
    "thing".r.findAllMatchIn(errorMessage) should have size 1
  }
  // TODO test for referencing non-existent partial

}
