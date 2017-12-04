package com.ovoenergy.comms.composer.sms

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.rendering.templating.{SMSTemplateData, SMSTemplateRendering}
import com.ovoenergy.comms.composer.repo.S3TemplateRepo
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms.OrchestratedSMSV2
import com.ovoenergy.comms.templates.TemplatesContext
import com.ovoenergy.comms.composer.Interpreters._

import scala.util.control.NonFatal

object SMSInterpreter {

  def apply(context: TemplatesContext): SMSComposerA ~> FailedOr =
    new (SMSComposerA ~> FailedOr) {
      override def apply[A](op: SMSComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getSMSTemplate(event.metadata.commManifest)
                .run(context)
                .leftMap(err => failSMS(err, event, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failSMSWithException(e, event))
            }
          case Render(event, template) =>
            try {
              SMSTemplateRendering
                .renderSMS(Clock.systemDefaultZone(),
                           event.metadata.commManifest,
                           template,
                           SMSTemplateData(event.templateData, event.customerProfile, event.recipientPhoneNumber))
                .leftMap(templateErrors => failSMS(templateErrors.reason, event, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failSMSWithException(e, event))
            }
        }
      }
    }

  private def failSMSWithException(exception: Throwable, event: OrchestratedSMSV2): FailedV2 = {
    warnT(event)(s"Failed to compose SMS because an unexpected exception occurred", exception)
    buildFailedEvent(s"Exception occurred ($exception)", event.metadata, event.internalMetadata, CompositionError)
  }

  private def failSMS(reason: String, event: OrchestratedSMSV2, errorCode: ErrorCode): FailedV2 = {
    warn(event)(s"Failed to compose SMS. Reason: $reason")
    buildFailedEvent(reason, event.metadata, event.internalMetadata, errorCode)
  }

}
