package com.ovoenergy.comms.composer.sms

import com.ovoenergy.comms.composer.ComposerError
import com.ovoenergy.comms.model.{FailedV3, MetadataV3}
import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.sms.OrchestratedSMSV3

sealed trait BuildFailedEventFrom[InEvent] {
  def apply(inEvent: InEvent, error: ComposerError): FailedV3
}

object BuildFailedEventFrom {

  implicit val failedFromOrchestratedEmail = new BuildFailedEventFrom[OrchestratedEmailV4] {
    override def apply(inEvent: OrchestratedEmailV4, error: ComposerError) = {
      FailedV3(
        MetadataV3.fromSourceMetadata("comms-composer", inEvent.metadata),
        inEvent.internalMetadata,
        error.reason,
        error.errorCode
      )
    }
  }

  implicit val failedFromOrchestratedSMS = new BuildFailedEventFrom[OrchestratedSMSV3] {
    override def apply(inEvent: OrchestratedSMSV3, error: ComposerError) = {
      FailedV3(
        MetadataV3.fromSourceMetadata("comms-composer", inEvent.metadata),
        inEvent.internalMetadata,
        error.reason,
        error.errorCode
      )
    }
  }

  implicit val failedFromOrchestratedPrint = new BuildFailedEventFrom[OrchestratedPrintV2] {
    override def apply(inEvent: OrchestratedPrintV2, error: ComposerError) = {
      FailedV3(
        MetadataV3.fromSourceMetadata("comms-composer", inEvent.metadata),
        inEvent.internalMetadata,
        error.reason,
        error.errorCode
      )
    }
  }
}
