package com.ovoenergy.comms.composer.v2

import cats.Id
import cats.data.Validated.Valid
import cats.effect.{Effect, IO}
import com.ovoenergy.comms.model.{Email, Print, SMS, TemplateManifest}
import com.ovoenergy.comms.templates.cache.CachingStrategy
import com.ovoenergy.comms.templates.model.RequiredTemplateData.string
import com.ovoenergy.comms.templates.model.{FileFormat, HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.files.TemplateFile
import com.ovoenergy.comms.templates.model.template.files.email.EmailTemplateFiles
import com.ovoenergy.comms.templates.model.template.files.print.PrintTemplateFiles
import com.ovoenergy.comms.templates.model.template.files.sms.SMSTemplateFiles
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import com.ovoenergy.comms.templates.parsing.handlebars.HandlebarsParsing
import com.ovoenergy.comms.templates.{ErrorsOr, TemplatesContext}
import com.ovoenergy.comms.templates.retriever.{PartialsRetriever, TemplatesRetriever}
import org.scalatest.{FlatSpec, Matchers}

class TemplatesSpec extends FlatSpec with Matchers {

  val requiredFields =
    RequiredTemplateData.obj(Map[String, RequiredTemplateData]("firstName" -> string))

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
      Templates.sms[IO](Effect[IO], emptyTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template has no channels defined"
  }

  it should "retrieve non existent email template" in {
    intercept[RuntimeException] {
      Templates.email[IO](Effect[IO], emptyTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template has no channels defined"
  }

  it should "retrieve non existent print template" in {
    intercept[RuntimeException] {
      Templates.print[IO](Effect[IO], emptyTemplatesContext).get(manifest).unsafeRunSync()
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
      Templates.email[IO](Effect[IO], partialTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template for channel not found"
  }

  it should "retrieve non existent print channel template" in {
    intercept[RuntimeException] {
      Templates.print[IO](Effect[IO], partialTemplatesContext).get(manifest).unsafeRunSync()
    }.getMessage shouldBe "Template for channel not found"
  }

}
