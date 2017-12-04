package com.ovoenergy.comms.composer

import com.ovoenergy.comms.model._

object Interpreters extends Logging {

  type FailedOr[A] = Either[Error, A]

  case class Error(reason: String, errorCode: ErrorCode)

}
