package com.ovoenergy.comms.composer
import java.time.ZonedDateTime

import cats.effect.Sync

trait Time[F[_]] {
  def now: F[ZonedDateTime]
}

object Time {

  def apply[F[_]: Sync]: Time[F] = new Time[F] {
    def now: F[ZonedDateTime] = Sync[F].delay(ZonedDateTime.now())
  }
}
