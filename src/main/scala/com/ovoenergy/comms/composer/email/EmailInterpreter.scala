package com.ovoenergy.comms.composer.email

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.rendering.templating.{EmailTemplateData, EmailTemplateRendering}
import com.ovoenergy.comms.composer.repo.S3TemplateRepo
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.composer.Interpreters._
import com.ovoenergy.comms.templates.TemplatesContext

import scala.util.control.NonFatal

object EmailInterpreter {
  val emailTD: EmailTemplateData = ???

  def apply(context: TemplatesContext): EmailComposerA ~> FailedOr =
    new (EmailComposerA ~> FailedOr) {
      override def apply[A](op: EmailComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getEmailTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => failEmail(err, event, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e, event))
            }
          case Render(event, template) =>
            try {
              EmailTemplateRendering
                .renderEmail(
                  Clock.systemDefaultZone(),
                  event.metadata.commManifest,
                  template,
//                                                        event.templateData,
//                                                        event.customerProfile,
//                                                        event.recipientEmailAddress)
                  emailTD
                )
                .leftMap(templateErrors => failEmail(templateErrors.reason, event, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e, event))
            }
          case LookupSender(template, commType) =>
            Right(SenderLogic.chooseSender(template, commType))
        }
      }
    }

  private def failEmail(reason: String, event: OrchestratedEmailV3, errorCode: ErrorCode): FailedV2 = {
    warn(event)(s"Failed to compose email. Reason: $reason")
    buildFailedEvent(reason, event.metadata, event.internalMetadata, errorCode)
  }

  private def failEmailWithException(exception: Throwable, event: OrchestratedEmailV3): FailedV2 = {
    warnT(event)(s"Failed to compose Email because an unexpected exception occurred", exception)
    buildFailedEvent(s"Exception occurred ($exception)", event.metadata, event.internalMetadata, CompositionError)
  }

}
