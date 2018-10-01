package com.ovoenergy.comms.composer
package rendering

import com.ovoenergy.comms.model.{SMS, Print, Email, TemplateManifest}
import com.ovoenergy.comms.templates
import templates.retriever.{PartialsRetriever, TemplatesRetriever}
import templates.{ErrorsOr, TemplatesContext}
import templates.cache.CachingStrategy
import templates.model._
import templates.parsing.handlebars.HandlebarsParsing
import template.files.TemplateFile
import template.files.email.EmailTemplateFiles
import template.files.print.PrintTemplateFiles
import template.files.sms.SMSTemplateFiles
import template.processed.email.EmailTemplate
import template.processed.print.PrintTemplate
import template.processed.sms.SMSTemplate
import cats.Id
import cats.data.Validated.Valid
import cats.effect.{IO, Effect}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext

class TemplatesSpec extends FlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  val requiredFields =
    RequiredTemplateData.obj(
      Map[String, RequiredTemplateData]("firstName" -> RequiredTemplateData.string))

  implicit val templateContext: TemplatesContext = TemplatesContext(
    new TemplatesRetriever {
      override def getEmailTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[EmailTemplateFiles]] =
        Some(
          Valid(
            EmailTemplateFiles(
              TemplateFile(Email, FileFormat.Text, "Meter reading"),
              TemplateFile(Email, FileFormat.Html, "Hello {{firstName}}!"),
              None,
              None
            )))

      override def getSMSTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[SMSTemplateFiles]] =
        Some(Valid(SMSTemplateFiles(TemplateFile(SMS, FileFormat.Text, "Hello {{firstName}}!"))))

      override def getPrintTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[PrintTemplateFiles]] =
        Some(
          Valid(
            PrintTemplateFiles(
              TemplateFile(Print, FileFormat.Html, "Hello {{firstName}}!")
            )))
    },
    new HandlebarsParsing(new PartialsRetriever {
      override def getSharedPartial(
          referringFile: TemplateFile,
          partialName: String): Either[String, String] =
        Right("")
    }),
    CachingStrategy.noCache
  )

  val manifest = TemplateManifest("id", "1.0")

  it should "retrieve sms template" in {
    val expected = SMSTemplate[Id](HandlebarsTemplate("Hello {{firstName}}!", requiredFields))
    Templates.sms[IO].get(manifest).unsafeRunSync() shouldBe expected
  }

  it should "retrieve email template" in {
    val expected = EmailTemplate[Id](
      HandlebarsTemplate(
        "Meter reading",
        RequiredTemplateData.obj(Map[String, RequiredTemplateData]())),
      HandlebarsTemplate("Hello {{firstName}}!", requiredFields),
      None,
      None
    )
    Templates.email[IO].get(manifest).unsafeRunSync() shouldBe expected
  }

  it should "retrieve print template" in {
    val expected = PrintTemplate[Id](HandlebarsTemplate("Hello {{firstName}}!", requiredFields))
    Templates.print[IO].get(manifest).unsafeRunSync() shouldBe expected
  }

  val emptyTemplatesContext: TemplatesContext = TemplatesContext(
    new TemplatesRetriever {
      override def getEmailTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[EmailTemplateFiles]] =
        None

      override def getSMSTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[SMSTemplateFiles]] =
        None

      override def getPrintTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[PrintTemplateFiles]] =
        None
    },
    new HandlebarsParsing(new PartialsRetriever {
      override def getSharedPartial(
          referringFile: TemplateFile,
          partialName: String): Either[String, String] =
        Right("")
    }),
    CachingStrategy.noCache
  )

  it should "retrieve non existent sms template" in {
    intercept[RuntimeException] {
      Templates.sms[IO](Effect[IO], ec, emptyTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template has no channels defined"
  }

  it should "retrieve non existent email template" in {
    intercept[RuntimeException] {
      Templates.email[IO](Effect[IO], ec, emptyTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template has no channels defined"
  }

  it should "retrieve non existent print template" in {
    intercept[RuntimeException] {
      Templates.print[IO](Effect[IO], ec, emptyTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template has no channels defined"
  }

  val partialTemplatesContext: TemplatesContext = TemplatesContext(
    new TemplatesRetriever {
      override def getEmailTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[EmailTemplateFiles]] =
        None

      override def getSMSTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[SMSTemplateFiles]] =
        Some(Valid(SMSTemplateFiles(TemplateFile(SMS, FileFormat.Text, "Hello {{firstName}}!"))))

      override def getPrintTemplate(
          templateManifest: TemplateManifest): Option[ErrorsOr[PrintTemplateFiles]] =
        None
    },
    new HandlebarsParsing(new PartialsRetriever {
      override def getSharedPartial(
          referringFile: TemplateFile,
          partialName: String): Either[String, String] =
        Right("")
    }),
    CachingStrategy.noCache
  )

  it should "retrieve sms channel template" in {
    val expected = SMSTemplate[Id](HandlebarsTemplate("Hello {{firstName}}!", requiredFields))
    Templates.sms[IO].get(manifest).unsafeRunSync() shouldBe expected
  }

  it should "retrieve non existent email channel template" in {
    intercept[RuntimeException] {
      Templates.email[IO](Effect[IO], ec, partialTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template for channel not found"
  }

  it should "retrieve non existent print channel template" in {
    intercept[RuntimeException] {
      Templates.print[IO](Effect[IO], ec, partialTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template for channel not found"
  }

}
