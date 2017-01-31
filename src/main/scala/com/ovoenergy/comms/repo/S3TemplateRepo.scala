package com.ovoenergy.comms.repo

import cats.Id
import cats.data.ReaderT
import cats.data.Validated.{Invalid, Valid}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.{TemplatesContext, TemplatesRepo}

object S3TemplateRepo {

  type ErrorOr[A] = Either[String, A]
  // TODO caching: cache template files indefinitely, fragments for 5 minutes
  def getEmailTemplate(commManifest: CommManifest) = ReaderT[ErrorOr, TemplatesContext, EmailTemplate[Id]] { context =>
    val emailTemplate = TemplatesRepo.getTemplate(context, commManifest).map(_.email)
    emailTemplate match {
      case Invalid(errs) => {
        Left(
          s"""The specified template (${commManifest.commType}, ${commManifest.name}, ${commManifest.version}) does not exist or is not valid.
                |The following errors were encountered:
                |
                   |${errs.toList.map(e => s" - $e").mkString("\n")}
                |""".stripMargin)
      }
      case Valid(None) =>
        Left(
          s"The specified template (${commManifest.commType}, ${commManifest.name}, ${commManifest.version}) does not contain an email template.")

      case Valid(Some(result)) => Right(result)
    }
  }
}
