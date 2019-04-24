package com.ovoenergy.comms.composer
import java.time.Instant

import cats.effect.Sync

trait Time[F[_]] {
  def now: F[Instant]
}

object Time {

  def apply[F[_]: Sync]: Time[F] = new Time[F] {
    def now: F[Instant] = Sync[F].delay(Instant.now())
  }
}
