package com.ovoenergy.comms

import com.ovoenergy.comms.model.ErrorCode

package object composer {

  type CommId = String

  type TraceToken = String

  type FailedOr[A] = Either[ComposerError, A]

  case class ComposerError(reason: String, errorCode: ErrorCode)
}
