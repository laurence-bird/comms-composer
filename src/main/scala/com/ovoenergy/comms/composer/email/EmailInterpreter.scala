package com.ovoenergy.comms.composer.email

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.{ComposerError, FailedOr, Logging}
import com.ovoenergy.comms.composer.rendering.templating.{EmailTemplateData, EmailTemplateRendering}
import com.ovoenergy.comms.composer.repo.S3TemplateRepo
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.templates.TemplatesContext

import scala.util.control.NonFatal

object EmailInterpreter extends Logging {
  def apply(context: TemplatesContext): EmailComposerA ~> FailedOr =
    new (EmailComposerA ~> FailedOr) {
      override def apply[A](op: EmailComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(event) =>
            try {
              val result = S3TemplateRepo
                .getEmailTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => failEmail(err, TemplateDownloadFailed))
              warn(event)(s"Failed to retrieve Email template")
              result.left.map(e => warn(event)(s"Failed to retrieve Email template: ${e.reason}"))
              result
            } catch {
              case NonFatal(e) => {
                warnWithException(event)("Failed to retrieve email template")(e)
                Left(failEmailWithException(e))
              }
            }
          case Render(event, template) =>
            try {
              val result: Either[ComposerError, RenderedEmail] = EmailTemplateRendering
                .renderEmail(
                  Clock.systemDefaultZone(),
                  event.metadata.commManifest,
                  template,
                  EmailTemplateData(event.templateData, event.customerProfile, event.recipientEmailAddress)
                )
                .leftMap(templateErrors => failEmail(templateErrors.reason, templateErrors.errorCode))
              result.fold(e => warn(event)(s"Failed to render Email: ${e.reason}"),
                          _ => info(event)("Rendered Email successfully"))
              result
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e))
            }
          case LookupSender(template, commType) =>
            Right(SenderLogic.chooseSender(template, commType))
        }
      }
    }

  private def failEmail(reason: String, errorCode: ErrorCode): ComposerError = {
    ComposerError(reason, errorCode)
  }

  private def failEmailWithException(exception: Throwable): ComposerError = {
    ComposerError(s"Exception occurred ($exception)", CompositionError)
  }

}
