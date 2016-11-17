package com.ovoenergy.comms

import org.scalatest._

class RenderingSpec extends FlatSpec with Matchers {

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

    val result = Rendering.render(manifest, template, data, profile)
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

    val result = Rendering.render(manifest, template, data, profile)
    result.subject should be("Thanks for your payment of £1.23")
    result.htmlBody should be("HTML HEADER You paid £1.23 HTML FOOTER")
    result.textBody should be(Some("TEXT HEADER The amount was £1.23 TEXT FOOTER"))
  }

}
