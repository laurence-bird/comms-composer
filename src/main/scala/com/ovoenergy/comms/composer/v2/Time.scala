package com.ovoenergy.comms.composer.v2

import java.time.ZonedDateTime

trait Time[F[_]] {
  def now: F[ZonedDateTime]
}
