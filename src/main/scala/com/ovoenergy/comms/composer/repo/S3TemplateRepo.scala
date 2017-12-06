package com.ovoenergy.comms.composer.repo

import cats.Id
import cats.data.{ReaderT, ValidatedNel}
import cats.data.Validated.{Invalid, Valid}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import com.ovoenergy.comms.templates.{ErrorsOr, TemplatesContext, TemplatesRepo}

object S3TemplateRepo {

  type ErrorOr[A] = Either[String, A]

  // TODO caching: cache template files indefinitely, fragments for 5 minutes

  def getEmailTemplate(commManifest: CommManifest) = ReaderT[ErrorOr, TemplatesContext, EmailTemplate[Id]] { context =>
    val template = TemplatesRepo.getTemplate(context, commManifest).map(_.email)
    wrangle(template, commManifest).flatMap(_.flattenOption(commManifest))
  }

  def getSMSTemplate(commManifest: CommManifest) = ReaderT[ErrorOr, TemplatesContext, SMSTemplate[Id]] { context =>
    val template = TemplatesRepo.getTemplate(context, commManifest).map(_.sms)
    wrangle(template, commManifest).flatMap(_.flattenOption(commManifest))
  }

  def getPrintTemplate(commManifest: CommManifest) = ReaderT[ErrorOr, TemplatesContext, PrintTemplate[Id]] { context =>
    val template = TemplatesRepo.getTemplate(context, commManifest).map(_.print)
    wrangle(template, commManifest).flatMap(_.flattenOption(commManifest))
  }

  def getTemplate(commManifest: CommManifest) = ReaderT[ErrorOr, TemplatesContext, CommTemplate[Id]] { context =>
    val template = TemplatesRepo.getTemplate(context, commManifest)
    wrangle(template, commManifest)
  }

  /*
  Mapping the ValidatedNel to an Either[String, A]
   */
  private def wrangle[A](validated: ValidatedNel[String, A], commManifest: CommManifest): ErrorOr[A] =
    validated match {
      case Invalid(errs) =>
        Left(
          s"""The specified template (${commManifest.commType}, ${commManifest.name}, ${commManifest.version}) does not exist or is not valid.
           |The following errors were encountered:
           |
                 |${errs.toList.map(e => s" - $e").mkString("\n")}
           |""".stripMargin)

      case Valid(result) => Right(result)
    }

  implicit class OptionExtensions[A](opt: Option[A]) {

    def flattenOption(commManifest: CommManifest) = {
      opt match {
        case Some(a) => Right(a)
        case None =>
          Left(
            s"The specified template (${commManifest.commType}, ${commManifest.name}, ${commManifest.version}) does not contain an email template.")
      }
    }
  }
}
