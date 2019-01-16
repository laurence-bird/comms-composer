package com.ovoenergy.comms.composer
package rendering

import java.time.ZonedDateTime

import com.ovoenergy.comms.composer.rendering.templating.EmailTemplateData
import com.ovoenergy.comms.model.{CustomerProfile, Arbitraries, TemplateData}
import com.ovoenergy.comms.templates.model.{RequiredTemplateData, HandlebarsTemplate}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.OptionValues

class HandlebarsRenderingSpec extends UnitSpec with OptionValues {

  behavior of "HtmlRendering"

  val requiredTemplateData = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())

  implicit val arbEmailTemplateData: Arbitrary[EmailTemplateData] = Arbitrary {
    for {
      td <- Arbitrary.arbitrary[Map[String, TemplateData]]
      pf <- Gen.option(Arbitrary.arbitrary[CustomerProfile])
      r <- Gen.alphaNumStr
    } yield EmailTemplateData(td, pf, r)
  }

  it should "generate the correct handlebarsContext for a given email template" in {
    var contextInput: Map[String, AnyRef] = Map.empty
    var contentInput: String = ""
    var fileNameInput: String = ""

    val balanceMap = Map("total" -> "50", "currency" -> "GBP")
    val td = Map("balance" -> TemplateData.fromMap(balanceMap.mapValues(TemplateData.fromString)))
    val emailTemplateData =
      EmailTemplateData(td, Some(CustomerProfile("Mr", "T")), "treatyomotherright@gmail.com")
    val fileName = "some-file"

    val handlebars = new HandlebarsWrapper {
      override def compile(
          fileName: String,
          templateRawContent: String,
          context: Map[String, AnyRef]): Either[Errors, String] = {
        contextInput = context
        contentInput = templateRawContent
        fileNameInput = fileName

        Right("hi")
      }
    }
    val htmlRendering = HandlebarsRendering.apply(handlebars)

    val now = ZonedDateTime.now()
    val ht = HandlebarsTemplate("hi", requiredTemplateData)

    val result = htmlRendering.render(ht, now, emailTemplateData, fileName)
    result shouldBe 'right
    contentInput shouldBe ht.rawExpandedContent
    fileNameInput shouldBe fileName
    contextInput.get("balance").value.asInstanceOf[Map[String, String]] shouldBe balanceMap
    contextInput.get("system").value.asInstanceOf[Map[String, String]] shouldBe Map(
      "year" -> now.getYear.toString,
      "month" -> now.getMonth.getValue.toString,
      "dayOfMonth" -> now.getDayOfMonth.toString)
    contextInput.get("profile").value.asInstanceOf[Map[String, String]] shouldBe Map(
      "firstName" -> emailTemplateData.customerProfile.value.firstName,
      "lastName" -> emailTemplateData.customerProfile.value.lastName)
  }

  it should "Ensure if template field name clashes occur, templateData takes precedence over system, and profile variables" in {
    var contextInput: Map[String, AnyRef] = Map.empty
    var contentInput: String = ""
    var fileNameInput: String = ""
    val now = ZonedDateTime.now()

    val profileTdMap = Map("firstName" -> "Mr", "lastName" -> "T")
    val systemTdMap = Map("year" -> "1990", "month" -> "9", "dayOfMonth" -> "08")
    val td = Map(
      "profile" -> TemplateData.fromMap(profileTdMap.mapValues(TemplateData.fromString)),
      "system" -> TemplateData.fromMap(systemTdMap.mapValues(TemplateData.fromString))
    )
    val emailTemplateData =
      EmailTemplateData(td, Some(CustomerProfile("Mr", "T")), "treatyomotherright@gmail.com")
    val fileName = "some-file"

    val handlebars = new HandlebarsWrapper {
      override def compile(
          fileName: String,
          templateRawContent: String,
          context: Map[String, AnyRef]): Either[Errors, String] = {
        contextInput = context
        contentInput = templateRawContent
        fileNameInput = fileName

        Right("hi")
      }
    }
    val htmlRendering = HandlebarsRendering.apply(handlebars)

    val ht = HandlebarsTemplate("hi", requiredTemplateData)

    val result = htmlRendering.render(ht, now, emailTemplateData, fileName)
    result shouldBe 'right
    contentInput shouldBe ht.rawExpandedContent
    fileNameInput shouldBe fileName
    contextInput.get("system").value.asInstanceOf[Map[String, String]] shouldBe systemTdMap
    contextInput.get("profile").value.asInstanceOf[Map[String, String]] shouldBe profileTdMap
  }
}
