package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

trait Time[F[_]] {
  def now: F[ZonedDateTime]
}
