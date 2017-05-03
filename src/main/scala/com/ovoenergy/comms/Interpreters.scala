package com.ovoenergy.comms

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.email.{EmailComposerA, SenderLogic}
import com.ovoenergy.comms.model.ErrorCode.{CompositionError, TemplateDownloadFailed}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.rendering.Rendering
import com.ovoenergy.comms.repo.S3TemplateRepo
import com.ovoenergy.comms.sms.SMSComposerA
import com.ovoenergy.comms.templates.TemplatesContext
import com.ovoenergy.comms.types.{HasInternalMetadata, HasMetadata}

import scala.util.control.NonFatal

object Interpreters extends Logging {

  type FailedOr[A] = Either[FailedV2, A]

  def emailInterpreter(context: TemplatesContext): EmailComposerA ~> FailedOr =
    new (EmailComposerA ~> FailedOr) {
      override def apply[A](op: EmailComposerA[A]): FailedOr[A] = {
        op match {
          case email.RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getEmailTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => failEmail(err, event, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e, event))
            }
          case email.Render(event, template) =>
            try {
              Rendering
                .renderEmail(Clock.systemDefaultZone())(event.metadata.commManifest,
                                                        template,
                                                        event.templateData,
                                                        event.customerProfile,
                                                        event.recipientEmailAddress)
                .leftMap(templateErrors => failEmail(templateErrors.reason, event, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failEmailWithException(e, event))
            }
          case email.LookupSender(template, commType) =>
            Right(SenderLogic.chooseSender(template, commType))
        }
      }
    }

  def smsInterpreter(context: TemplatesContext): SMSComposerA ~> FailedOr =
    new (SMSComposerA ~> FailedOr) {
      override def apply[A](op: SMSComposerA[A]): FailedOr[A] = {
        op match {
          case sms.RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getSMSTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => failSMS(err, event, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failSMSWithException(e, event))
            }
          case sms.Render(event, template) =>
            try {
              Rendering
                .renderSMS(Clock.systemDefaultZone())(event.metadata.commManifest,
                                                      template,
                                                      event.templateData,
                                                      event.customerProfile,
                                                      event.recipientPhoneNumber)
                .leftMap(templateErrors => failSMS(templateErrors.reason, event, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failSMSWithException(e, event))
            }
        }
      }
    }

  private def failEmail(reason: String, event: OrchestratedEmailV3, errorCode: ErrorCode): FailedV2 = {
    warn(event)(s"Failed to compose email. Reason: $reason")
    buildFailedEvent(reason, event.metadata, event.internalMetadata, errorCode)
  }

  private def failSMS(reason: String, event: OrchestratedSMSV2, errorCode: ErrorCode): FailedV2 = {
    warn(event)(s"Failed to compose SMS. Reason: $reason")
    buildFailedEvent(reason, event.metadata, event.internalMetadata, errorCode)
  }

  private def failEmailWithException(exception: Throwable, event: OrchestratedEmailV3): FailedV2 = {
    warnT(event)(s"Failed to compose Email because an unexpected exception occurred", exception)
    buildFailedEvent(s"Exception occurred ($exception)", event.metadata, event.internalMetadata, CompositionError)
  }

  private def failSMSWithException(exception: Throwable, event: OrchestratedSMSV2): FailedV2 = {
    warnT(event)(s"Failed to compose SMS because an unexpected exception occurred", exception)
    buildFailedEvent(s"Exception occurred ($exception)", event.metadata, event.internalMetadata, CompositionError)
  }

  private def buildFailedEvent(reason: String,
                               metadata: MetadataV2,
                               internalMetadata: InternalMetadata,
                               errorCode: ErrorCode): FailedV2 =
    FailedV2(
      MetadataV2.fromSourceMetadata("comms-composer", metadata),
      internalMetadata,
      reason,
      errorCode
    )
}
