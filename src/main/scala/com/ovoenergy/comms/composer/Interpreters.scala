package com.ovoenergy.comms.composer

import com.ovoenergy.comms.model._

object Interpreters extends Logging {

  type FailedOr[A] = Either[FailedV2, A]

  def buildFailedEvent(reason: String,
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
