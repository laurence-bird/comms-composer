package com.ovoenergy.comms.composer.sms

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.Interpreters
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
                .leftMap(err => failSMS(err, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failSMSWithException(e))
            }
          case Render(event, template) =>
            try {
              SMSTemplateRendering
                .renderSMS(Clock.systemDefaultZone(),
                           event.metadata.commManifest,
                           template,
                           SMSTemplateData(event.templateData, event.customerProfile, event.recipientPhoneNumber))
                .leftMap(templateErrors => failSMS(templateErrors.reason, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failSMSWithException(e))
            }
        }
      }
    }

  private def failSMS(reason: String, errorCode: ErrorCode): Interpreters.Error = {
    Interpreters.Error(reason, errorCode)
  }

  private def failSMSWithException(exception: Throwable): Interpreters.Error = {
    Interpreters.Error(s"Exception occurred ($exception)", CompositionError)
  }
}
