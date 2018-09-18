package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

import cats.data.Validated
import cats.data.Validated.Valid
import com.ovoenergy.comms.composer.rendering
import com.ovoenergy.comms.composer.rendering.templating.EmailTemplateData
import com.ovoenergy.comms.model.{Arbitraries, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class HtmlRenderingSpec extends FlatSpec with Matchers with Arbitraries with TestGenerators with EitherValues{

  behavior of "HtmlRendering"

  val requiredTemplateData = RequiredTemplateData.obj(Map[String, RequiredTemplateData]())

  implicit val arbEmailTemplateData = Arbitrary{
    for {
      td <- Arbitrary.arbitrary[Map[String, TemplateData]]
      pf <- Gen.option(Arbitrary.arbitrary[CustomerProfile])
      r  <- Gen.alphaNumStr
    } yield EmailTemplateData(td, pf, r)
  }

  it should "generate the correct handlebarsContext for a given template" in {
    var contextInput: Map[String, AnyRef] = Map.empty
    var contentInput: String = ""
    var fileNameInput: String = ""

    val td = generate[EmailTemplateData]
    val fileName = generate[String]

    val handlebars = new HandlebarsWrapped {
      override def compile(fileName: String, templateRawContent: String, context: Map[String, AnyRef]): Validated[rendering.Errors, String] = {
        contextInput  = context
        contentInput  = templateRawContent
        fileNameInput = fileName

        Valid("hi")
      }
    }

    val htmlRendering = HtmlRendering.apply(handlebars)

    val now = ZonedDateTime.now()
    val ht = HandlebarsTemplate("hi", requiredTemplateData)

    val result = htmlRendering.render(ht, now, td, fileName).toEither.right.value

    contentInput  shouldBe ht.rawExpandedContent
    fileNameInput shouldBe fileName
    td.templateData.toList.foreach{ td: (String, TemplateData) =>
      contextInput.get(td._1) shouldBe 'defined
    }
  }

}
