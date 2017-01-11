package com.ovoenergy.comms.email

import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms._
import com.ovoenergy.comms.model.ErrorCode.{CompositionError, MissingTemplateData, TemplateDownloadFailed}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.repo.{S3Client, S3TemplateRepo}

import scala.util.control.NonFatal

object Interpreter extends Logging {

  type FailedOr[A] = Either[Failed, A]

  def build(s3client: S3Client)(incomingEvent: OrchestratedEmail): ComposerA ~> FailedOr =
    new (ComposerA ~> FailedOr) {
      override def apply[A](op: ComposerA[A]): FailedOr[A] = {
        try {
          op match {
            case RetrieveTemplate(channel, commManifest) =>
              // only supporting email for now
              S3TemplateRepo
                .getEmailTemplate(commManifest)
                .run(s3client)
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

  private def fail(reason: String, incomingEvent: OrchestratedEmail, errorCode: ErrorCode): Failed = {
    warn(incomingEvent.metadata.traceToken)(s"Failed to compose email. Reason: $reason")
    buildFailedEvent(reason, incomingEvent, errorCode)
  }

  private def failWithException(exception: Throwable, incomingEvent: OrchestratedEmail): Failed = {
    warnE(incomingEvent.metadata.traceToken)(s"Failed to compose email because an unexpected exception occurred",
                                             exception)
    buildFailedEvent(s"Exception occurred ($exception)", incomingEvent, CompositionError)
  }

  private def buildFailedEvent(reason: String, incomingEvent: OrchestratedEmail, errorCode: ErrorCode): Failed =
    Failed(
      Metadata.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      incomingEvent.internalMetadata,
      reason,
      errorCode
    )
}
