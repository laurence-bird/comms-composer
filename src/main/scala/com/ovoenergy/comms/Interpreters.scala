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

  type FailedOr[A] = Either[Failed, A]

  def emailInterpreter(context: TemplatesContext): EmailComposerA ~> FailedOr =
    new (EmailComposerA ~> FailedOr) {
      override def apply[A](op: EmailComposerA[A]): FailedOr[A] = {
        op match {
          case email.RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getEmailTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => fail(err, event, TemplateDownloadFailed, "email"))
            } catch {
              case NonFatal(e) => Left(failWithException(e, event, "email"))
            }
          case email.Render(event, template) =>
            try {
              Rendering
                .renderEmail(Clock.systemDefaultZone())(event.metadata.commManifest,
                                                        template,
                                                        event.templateData,
                                                        event.customerProfile,
                                                        event.recipientEmailAddress)
                .leftMap(templateErrors => fail(templateErrors.reason, event, templateErrors.errorCode, "email"))
            } catch {
              case NonFatal(e) => Left(failWithException(e, event, "email"))
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
                .leftMap(err => fail(err, event, TemplateDownloadFailed, "SMS"))
            } catch {
              case NonFatal(e) => Left(failWithException(e, event, "SMS"))
            }
          case sms.Render(event, template) =>
            try {
              Rendering
                .renderSMS(Clock.systemDefaultZone())(event.metadata.commManifest,
                                                      template,
                                                      event.templateData,
                                                      event.customerProfile,
                                                      event.recipientPhoneNumber)
                .leftMap(templateErrors => fail(templateErrors.reason, event, templateErrors.errorCode, "SMS"))
            } catch {
              case NonFatal(e) => Left(failWithException(e, event, "SMS"))
            }
        }
      }
    }

  private def fail[A <: HasMetadata with HasInternalMetadata](reason: String,
                                                              event: A,
                                                              errorCode: ErrorCode,
                                                              channel: String): Failed = {
    warn(event)(s"Failed to compose $channel. Reason: $reason")
    buildFailedEvent(reason, event.metadata, event.internalMetadata, errorCode)
  }

  private def failWithException[A <: HasMetadata with HasInternalMetadata](exception: Throwable,
                                                                           event: A,
                                                                           channel: String): Failed = {
    warnE(event)(s"Failed to compose $channel because an unexpected exception occurred", exception)
    buildFailedEvent(s"Exception occurred ($exception)", event.metadata, event.internalMetadata, CompositionError)
  }

  private def buildFailedEvent(reason: String,
                               metadata: Metadata,
                               internalMetadata: InternalMetadata,
                               errorCode: ErrorCode): Failed =
    Failed(
      Metadata.fromSourceMetadata("comms-composer", metadata),
      internalMetadata,
      reason,
      errorCode
    )
}
