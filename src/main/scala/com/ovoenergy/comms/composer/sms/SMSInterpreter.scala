package com.ovoenergy.comms.composer.sms

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.email.HashString
import com.ovoenergy.comms.composer.{ComposerError, FailedOr, Logging}
import com.ovoenergy.comms.composer.rendering.templating.{SMSTemplateData, SMSTemplateRendering}
import com.ovoenergy.comms.composer.repo.S3TemplateRepo
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms.OrchestratedSMSV2
import com.ovoenergy.comms.templates.TemplatesContext
import com.ovoenergy.comms.templates.util.Hash

import scala.util.control.NonFatal

object SMSInterpreter extends Logging {

  def apply(context: TemplatesContext): SMSComposerA ~> FailedOr =
    new (SMSComposerA ~> FailedOr) {
      override def apply[A](op: SMSComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getSMSTemplate(event.metadata.templateManifest)
                .run(context)
                .leftMap { err =>
                  warn(event)(s"Failed to retrieve template: $err")
                  failSMS(err, TemplateDownloadFailed)
                }
            } catch {
              case NonFatal(e) => {
                warn(event)("Failed to retrieve template")
                Left(failSMSWithException(e))
              }
            }
          case Render(event, template) =>
            try {
              val result = SMSTemplateRendering
                .renderSMS(
                  Clock.systemDefaultZone(),
                  event.metadata.templateManifest,
                  template,
                  SMSTemplateData(
                    event.templateData,
                    event.customerProfile,
                    event.recipientPhoneNumber)
                )
                .leftMap { templateErrors =>
                  failSMS(templateErrors.reason, templateErrors.errorCode)
                }
              result.fold(
                e => warn(event)(s"Failed to render SMS: ${e.reason}"),
                _ => info(event)("Rendered SMS successfully"))

              result
            } catch {
              case NonFatal(e) => {
                warnWithException(event)(s"Failed to render SMS")(e)
                Left(failSMSWithException(e))
              }
            }
          case HashString(str) => Right(Hash(str))
        }
      }
    }

  private def failSMS(reason: String, errorCode: ErrorCode): ComposerError = {
    ComposerError(reason, errorCode)
  }

  private def failSMSWithException(exception: Throwable): ComposerError = {
    ComposerError(s"Exception occurred ($exception)", CompositionError)
  }
}
