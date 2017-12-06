package com.ovoenergy.comms.composer.sms

import com.ovoenergy.comms.composer.ComposerError
import com.ovoenergy.comms.model.{FailedV2, MetadataV2}
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.model.sms.OrchestratedSMSV2

sealed trait BuildFailedEventFrom[InEvent] {
  def apply(inEvent: InEvent, error: ComposerError): FailedV2
}

object BuildFailedEventFrom {

  implicit val failedFromOrchestratedEmail = new BuildFailedEventFrom[OrchestratedEmailV3] {
    override def apply(inEvent: OrchestratedEmailV3, error: ComposerError) = {
      FailedV2(
        MetadataV2.fromSourceMetadata("comms-composer", inEvent.metadata),
        inEvent.internalMetadata,
        error.reason,
        error.errorCode
      )
    }
  }

  implicit val failedFromOrchestratedSMS = new BuildFailedEventFrom[OrchestratedSMSV2] {
    override def apply(inEvent: OrchestratedSMSV2, error: ComposerError) = {
      FailedV2(
        MetadataV2.fromSourceMetadata("comms-composer", inEvent.metadata),
        inEvent.internalMetadata,
        error.reason,
        error.errorCode
      )
    }
  }

  implicit val failedFromOrchestratedPrint = new BuildFailedEventFrom[OrchestratedPrint] {
    override def apply(inEvent: OrchestratedPrint, error: ComposerError) = {
      FailedV2(
        MetadataV2.fromSourceMetadata("comms-composer", inEvent.metadata),
        inEvent.internalMetadata,
        error.reason,
        error.errorCode
      )
    }
  }
}
