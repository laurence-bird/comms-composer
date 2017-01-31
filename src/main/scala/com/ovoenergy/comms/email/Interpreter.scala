package com.ovoenergy.comms.email

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms._
import com.ovoenergy.comms.model.ErrorCode.{CompositionError, TemplateDownloadFailed}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.repo.S3TemplateRepo
import com.ovoenergy.comms.templates.TemplatesContext

import scala.util.control.NonFatal

object Interpreter extends Logging {

  type FailedOr[A] = Either[Failed, A]

  def build(context: TemplatesContext)(incomingEvent: OrchestratedEmailV2): ComposerA ~> FailedOr =
    new (ComposerA ~> FailedOr) {
      override def apply[A](op: ComposerA[A]): FailedOr[A] = {
        try {
          op match {
            case RetrieveTemplate(channel, commManifest) =>
              // only supporting email for now
              S3TemplateRepo
                .getEmailTemplate(commManifest)
                .run(context)
                .leftMap(err => fail(err, incomingEvent, TemplateDownloadFailed))
            case Render(commManifest, template, data, customerProfile, recipientEmailAddress) =>
              Rendering
                .renderEmail(Clock.systemDefaultZone())(commManifest,
                                                        template,
                                                        data,
                                                        customerProfile,
                                                        recipientEmailAddress)
                .leftMap(templateErrors => fail(templateErrors.reason, incomingEvent, templateErrors.errorCode))
            case LookupSender(template, commType) =>
              Right(SenderLogic.chooseSender(template, commType))
          }
        } catch {
          case NonFatal(e) => Left(failWithException(e, incomingEvent))
        }
      }
    }

  private def fail(reason: String, incomingEvent: OrchestratedEmailV2, errorCode: ErrorCode): Failed = {
    warn(incomingEvent.metadata.traceToken)(s"Failed to compose email. Reason: $reason")
    buildFailedEvent(reason, incomingEvent, errorCode)
  }

  private def failWithException(exception: Throwable, incomingEvent: OrchestratedEmailV2): Failed = {
    warnE(incomingEvent.metadata.traceToken)(s"Failed to compose email because an unexpected exception occurred",
                                             exception)
    buildFailedEvent(s"Exception occurred ($exception)", incomingEvent, CompositionError)
  }

  private def buildFailedEvent(reason: String, incomingEvent: OrchestratedEmailV2, errorCode: ErrorCode): Failed =
    Failed(
      Metadata.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      incomingEvent.internalMetadata,
      reason,
      errorCode
    )
}
