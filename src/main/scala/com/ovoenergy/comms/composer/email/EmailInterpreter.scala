package com.ovoenergy.comms.composer.email

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.Interpreters
import com.ovoenergy.comms.composer.rendering.templating.{EmailTemplateData, EmailTemplateRendering}
import com.ovoenergy.comms.composer.repo.S3TemplateRepo
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.composer.Interpreters._
import com.ovoenergy.comms.templates.TemplatesContext

import scala.util.control.NonFatal

object EmailInterpreter {
  def apply(context: TemplatesContext): EmailComposerA ~> FailedOr =
    new (EmailComposerA ~> FailedOr) {
      override def apply[A](op: EmailComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getEmailTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => failEmail(err, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e))
            }
          case Render(event, template) =>
            try {
              EmailTemplateRendering
                .renderEmail(
                  Clock.systemDefaultZone(),
                  event.metadata.commManifest,
                  template,
                  EmailTemplateData(event.templateData, event.customerProfile, event.recipientEmailAddress)
                )
                .leftMap(templateErrors => failEmail(templateErrors.reason, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e))
            }
          case LookupSender(template, commType) =>
            Right(SenderLogic.chooseSender(template, commType))
        }
      }
    }

  private def failEmail(reason: String, errorCode: ErrorCode): Interpreters.Error = {
    Interpreters.Error(reason, errorCode)
  }

  private def failEmailWithException(exception: Throwable): Interpreters.Error = {
    Interpreters.Error(s"Exception occurred ($exception)", CompositionError)
  }

}
