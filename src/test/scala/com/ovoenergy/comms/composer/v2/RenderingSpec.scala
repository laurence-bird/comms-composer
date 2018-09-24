package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

import cats.Id
import cats.effect.IO
import com.ovoenergy.comms.composer.rendering.templating.{
  CommTemplateData,
  EmailTemplateData,
  PrintTemplateData,
  SMSTemplateData
}
import com.ovoenergy.comms.composer.rendering.{Errors, templating}
import rendering.{HandlebarsRendering, Rendering}
import model.{Email, SMS}
import com.ovoenergy.comms.model.{Arbitraries, MissingTemplateData, TemplateManifest}
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{EitherValues, FlatSpec, Matchers}

import scala.collection.mutable

class RenderingSpec
    extends FlatSpec
    with Matchers
    with Arbitraries
    with TestGenerators
    with EitherValues {

  behavior of "renderEmail"

  implicit val arbEmailTemplateData: Arbitrary[EmailTemplateData] = Arbitrary(for {
    td <- genMapTemplateData(3)
    cp <- Gen.option(Arbitrary.arbitrary(arbCustomerProfile))
    ra <- genStringOfSize(10)
  } yield EmailTemplateData(td, cp, ra))

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

  it should "Combine successfully rendered elements into a rendered email" in {
    val emailTemplateData = generate[EmailTemplateData]
    val templateManifest = generate[TemplateManifest]

    var passedTime = new mutable.MutableList[ZonedDateTime]
    var passedTd = new mutable.MutableList[CommTemplateData]
    var timesCalled = 0

    val happyHtmlRendering = new HandlebarsRendering {
      override def render(
          template: HandlebarsTemplate,
          time: ZonedDateTime,
          templateData: templating.CommTemplateData,
          fileName: String): Either[Errors, String] = {
        passedTime += time
        passedTd += templateData
        timesCalled += 1
        Right(s"Result ${timesCalled}")
      }
    }
    val rendering = Rendering[IO](happyHtmlRendering)
    val now = ZonedDateTime.now()
    val result =
      rendering.renderEmail(now, templateManifest, emailTemplate, emailTemplateData).unsafeRunSync()

    passedTime.distinct.size shouldBe 1
    passedTime.head shouldBe now
    passedTd.distinct.size shouldBe 1
    passedTd.distinct.head shouldBe emailTemplateData
    timesCalled shouldBe 3

    val fragments = List(Some(result.html), Some(result.subject), result.text).flatten
    val fragmentStrings = fragments.collect {
      case subj: Email.Subject => subj.content
      case hb: Email.HtmlBody => hb.content
      case tb: Email.TextBody => tb.content
    }

    fragmentStrings should contain theSameElementsAs List("Result 1", "Result 2", "Result 3")
  }

  it should "Combine Errors, and throw ComposerError if HtmlRendering fails" in {
    val emailTemplateData = generate[EmailTemplateData]
    val templateManifest = generate[TemplateManifest]
    val now = ZonedDateTime.now()

    var passedTime = new mutable.MutableList[ZonedDateTime]
    var passedTd = new mutable.MutableList[CommTemplateData]
    var timesCalled = 0

    val unhappyHtmlRendering = new HandlebarsRendering {
      override def render(
          template: HandlebarsTemplate,
          time: ZonedDateTime,
          templateData: templating.CommTemplateData,
          fileName: String): Either[Errors, String] = {
        passedTime += time
        passedTd += templateData
        timesCalled += 1
        Left(Errors(Set(s"error $timesCalled"), Seq.empty[Throwable], MissingTemplateData))
      }
    }
    val rendering = Rendering[IO](unhappyHtmlRendering)
    val resultEither =
      rendering
        .renderEmail(now, templateManifest, emailTemplate, emailTemplateData)
        .attempt
        .unsafeRunSync()

    timesCalled shouldBe 3
    val result = resultEither.left.value
    result shouldBe a[ComposerError]
    result
      .asInstanceOf[ComposerError]
      .reason shouldBe "The template referenced the following non-existent keys: [error 1,error 2,error 3]"
  }

  behavior of "renderSms"

  val smsTemplate =
    SMSTemplate[Id](
      textBody = HandlebarsTemplate("{{firstName}} you paid £{{amount}}", requiredFields))

  implicit val arbSmsTemplateData: Arbitrary[SMSTemplateData] = Arbitrary(for {
    td <- genMapTemplateData(3)
    cp <- Gen.option(Arbitrary.arbitrary(arbCustomerProfile))
    ra <- genStringOfSize(10)
  } yield SMSTemplateData(td, cp, ra))

  it should "Combine successfully rendered elements into a rendered SMS" in {
    val smsTemplateData = generate[SMSTemplateData]
    val templateManifest = generate[TemplateManifest]
    val now = ZonedDateTime.now()

    var passedTime = new mutable.MutableList[ZonedDateTime]
    var passedTd = new mutable.MutableList[CommTemplateData]
    var timesCalled = 0

    val happyRendering = new HandlebarsRendering {
      override def render(
          template: HandlebarsTemplate,
          time: ZonedDateTime,
          templateData: CommTemplateData,
          fileName: String): Either[Errors, String] = {
        passedTime += time
        passedTd += templateData
        timesCalled += 1
        Right(s"Result $timesCalled")
      }
    }
    val rendering = Rendering[IO](happyRendering)

    val result: SMS.Rendered =
      rendering
        .renderSms(now, templateManifest, smsTemplate, smsTemplateData)
        .attempt
        .unsafeRunSync()
        .right
        .value

    result.textBody shouldBe SMS.Body("Result 1")
    passedTime.head shouldBe now
    passedTd.size shouldBe 1
    passedTd.head shouldBe smsTemplateData
  }

  it should "raise an error if sms rendering fails" in {
    val smsTemplateData = generate[SMSTemplateData]
    val templateManifest = generate[TemplateManifest]
    val now = ZonedDateTime.now()
    var passedTime = new mutable.MutableList[ZonedDateTime]
    var passedTd = new mutable.MutableList[CommTemplateData]
    var timesCalled = 0

    val errors = Errors(Set("oh no"), Seq.empty, MissingTemplateData)
    val unhappyRendering = new HandlebarsRendering {
      override def render(
          template: HandlebarsTemplate,
          time: ZonedDateTime,
          templateData: CommTemplateData,
          fileName: String): Either[Errors, String] = {
        passedTime += time
        passedTd += templateData
        timesCalled += 1
        Left(errors)
      }
    }
    val rendering = Rendering[IO](unhappyRendering)

    val result =
      rendering
        .renderSms(now, templateManifest, smsTemplate, smsTemplateData)
        .attempt
        .unsafeRunSync()
        .left
        .value

    passedTime.size shouldBe 1
    passedTime.head shouldBe now
    passedTd.size shouldBe 1
    passedTd.head shouldBe smsTemplateData
    result shouldBe a[ComposerError]
    val err = result.asInstanceOf[ComposerError]

    err.reason shouldBe s"The template referenced the following non-existent keys: [${errors.missingKeys
      .mkString(",")}]"
    err.errorCode shouldBe errors.errorCode
  }

  behavior of "renderPrintHtml"

  val printTemplate = PrintTemplate[Id](HandlebarsTemplate("You paid", requiredFields))

  implicit val arbPrintTemplateData: Arbitrary[PrintTemplateData] = Arbitrary(for {
    td <- genMapTemplateData(3)
    cp <- Gen.option(Arbitrary.arbitrary(arbCustomerProfile))
    ra <- genCustomerAddress
  } yield PrintTemplateData(td, cp, ra))

  it should "Present successfully rendered element into a rendered Print" in {
    val printTemplateData = generate[PrintTemplateData]
    val templateManifest = generate[TemplateManifest]
    val now = ZonedDateTime.now()

    var passedTime = new mutable.MutableList[ZonedDateTime]
    var passedTd = new mutable.MutableList[CommTemplateData]
    var timesCalled = 0

    val happyRendering = new HandlebarsRendering {
      override def render(
          template: HandlebarsTemplate,
          time: ZonedDateTime,
          templateData: CommTemplateData,
          fileName: String): Either[Errors, String] = {
        passedTime += time
        passedTd += templateData
        timesCalled += 1
        Right(s"Result $timesCalled")
      }
    }
    val rendering = Rendering[IO](happyRendering)

    val result = rendering
      .renderPrintHtml(now, templateManifest, printTemplate, printTemplateData)
      .attempt
      .unsafeRunSync()
      .right
      .value

    passedTime.head shouldBe now
    passedTd.size shouldBe 1
    passedTd.head shouldBe printTemplateData

    result.htmlBody shouldBe "Result 1"
  }

  it should "raise an error if print html rendering fails" in {
    val printTemplateData = generate[PrintTemplateData]
    val templateManifest = generate[TemplateManifest]
    val now = ZonedDateTime.now()
    var passedTime = new mutable.MutableList[ZonedDateTime]
    var passedTd = new mutable.MutableList[CommTemplateData]
    var timesCalled = 0

    val errors = Errors(Set("oh no"), Seq.empty, MissingTemplateData)
    val unhappyRendering = new HandlebarsRendering {
      override def render(
          template: HandlebarsTemplate,
          time: ZonedDateTime,
          templateData: CommTemplateData,
          fileName: String): Either[Errors, String] = {
        passedTime += time
        passedTd += templateData
        timesCalled += 1
        Left(errors)
      }
    }
    val rendering = Rendering[IO](unhappyRendering)

    val result = rendering
      .renderPrintHtml(now, templateManifest, printTemplate, printTemplateData)
      .attempt
      .unsafeRunSync()
      .left
      .value

    passedTime.size shouldBe 1
    passedTime.head shouldBe now
    passedTd.size shouldBe 1
    passedTd.head shouldBe printTemplateData
    result shouldBe a[ComposerError]
    val err = result.asInstanceOf[ComposerError]

    err.reason shouldBe s"The template referenced the following non-existent keys: [${errors.missingKeys
      .mkString(",")}]"
    err.errorCode shouldBe errors.errorCode
  }
}
