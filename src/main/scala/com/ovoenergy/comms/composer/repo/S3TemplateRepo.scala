package com.ovoenergy.comms.composer.repo

import cats.Id
import cats.data.{ReaderT, ValidatedNel}
import cats.data.Validated.{Invalid, Valid}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import com.ovoenergy.comms.templates.s3.S3Prefix
import com.ovoenergy.comms.templates.{ErrorsOr, TemplatesContext, TemplatesRepo}

object S3TemplateRepo {

  type ErrorOr[A] = Either[String, A]

  // TODO caching: cache template files indefinitely, fragments for 5 minutes

  def getEmailTemplate(templateManifest: TemplateManifest) =
    ReaderT[ErrorOr, TemplatesContext, EmailTemplate[Id]] { context =>
      val template = TemplatesRepo.getTemplate(context, templateManifest).map(_.email)
      wrangle(template, templateManifest).flatMap(_.flattenOption(templateManifest))
    }

  def getSMSTemplate(templateManifest: TemplateManifest) =
    ReaderT[ErrorOr, TemplatesContext, SMSTemplate[Id]] { context =>
      val template = TemplatesRepo.getTemplate(context, templateManifest).map(_.sms)
      wrangle(template, templateManifest).flatMap(_.flattenOption(templateManifest))
    }

  def getPrintTemplate(templateManifest: TemplateManifest) =
    ReaderT[ErrorOr, TemplatesContext, PrintTemplate[Id]] { context =>
      val template = TemplatesRepo.getTemplate(context, templateManifest).map(_.print)
      wrangle(template, templateManifest).flatMap(_.flattenOption(templateManifest))
    }

  def getTemplate(templateManifest: TemplateManifest) =
    ReaderT[ErrorOr, TemplatesContext, CommTemplate[Id]] { context =>
      val template = TemplatesRepo.getTemplate(context, templateManifest)
      wrangle(template, templateManifest)
    }

  /*
  Mapping the ValidatedNel to an Either[String, A]
   */
  private def wrangle[A](
      validated: ValidatedNel[String, A],
      templateManifest: TemplateManifest): ErrorOr[A] =
    validated match {
      case Invalid(errs) =>
        Left(s"""The specified template (${S3Prefix
                  .fromTemplateManifest(templateManifest)}) does not exist or is not valid.
           |The following errors were encountered:
           |
                 |${errs.toList.map(e => s" - $e").mkString("\n")}
           |""".stripMargin)

      case Valid(result) => Right(result)
    }

  implicit class OptionExtensions[A](opt: Option[A]) {

    def flattenOption(templateManifest: TemplateManifest) = {
      opt match {
        case Some(a) => Right(a)
        case None =>
          Left(
            s"The specified template (${S3Prefix.fromTemplateManifest(templateManifest)}) does not contain an email template.")
      }
    }
  }
}
