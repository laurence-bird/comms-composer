package com.ovoenergy.comms.composer.kafka

import com.ovoenergy.comms.composer.ComposerError
import com.ovoenergy.comms.composer.kafka.BuildFeedback.AllFeedback
import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.sms.OrchestratedSMSV3
import com.ovoenergy.comms.model._
import com.ovoenergy.kafka.common.event.EventMetadata

trait BuildFeedback[InEvent] {
  def apply(inEvent: InEvent, error: ComposerError): AllFeedback
}

object BuildFeedback {

  case class AllFeedback(legacy: FailedV3, latest: Feedback)

  private def extractCustomer(deliverTo: DeliverTo): Option[Customer] = {
    deliverTo match {
      case customer: Customer => Some(customer)
      case _                  => None
    }
  }

  implicit val feedbackFromOrchestratedEmail = new BuildFeedback[OrchestratedEmailV4] {
    override def apply(inEvent: OrchestratedEmailV4, error: ComposerError) = {
      AllFeedback(
        FailedV3(
          MetadataV3.fromSourceMetadata("comms-composer", inEvent.metadata, s"${inEvent.metadata.eventId}-failed"),
          inEvent.internalMetadata,
          error.reason,
          error.errorCode
        ),
        Feedback(
          inEvent.metadata.commId,
          extractCustomer(inEvent.metadata.deliverTo),
          FeedbackOptions.Failed,
          Some(error.reason),
          None,
          Some(Email),
          EventMetadata.fromMetadata(inEvent.metadata, s"${inEvent.metadata.eventId}-feedback")
        )
      )
    }
  }

  implicit val feedbackFromOrchestratedSMS = new BuildFeedback[OrchestratedSMSV3] {
    override def apply(inEvent: OrchestratedSMSV3, error: ComposerError) = {
      AllFeedback(
        FailedV3(
          MetadataV3.fromSourceMetadata("comms-composer", inEvent.metadata, s"${inEvent.metadata.eventId}-failed"),
          inEvent.internalMetadata,
          error.reason,
          error.errorCode
        ),
        Feedback(
          inEvent.metadata.commId,
          extractCustomer(inEvent.metadata.deliverTo),
          FeedbackOptions.Failed,
          Some(error.reason),
          None,
          Some(SMS),
          EventMetadata.fromMetadata(inEvent.metadata, s"${inEvent.metadata.eventId}-feedback")
        )
      )
    }
  }

  implicit val feedbackFromOrchestratedPrint = new BuildFeedback[OrchestratedPrintV2] {
    override def apply(inEvent: OrchestratedPrintV2, error: ComposerError) = {
      AllFeedback(
        FailedV3(
          MetadataV3.fromSourceMetadata("comms-composer", inEvent.metadata, s"${inEvent.metadata.eventId}-failed"),
          inEvent.internalMetadata,
          error.reason,
          error.errorCode
        ),
        Feedback(
          inEvent.metadata.commId,
          extractCustomer(inEvent.metadata.deliverTo),
          FeedbackOptions.Failed,
          Some(error.reason),
          None,
          Some(Print),
          EventMetadata.fromMetadata(inEvent.metadata, s"${inEvent.metadata.eventId}-feedback")
        )
      )
    }
  }
}
