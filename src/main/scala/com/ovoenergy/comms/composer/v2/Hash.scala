package com.ovoenergy.comms.composer.v2

trait Hash[F[_]] {
  def apply[A](a: A): F[String]
}
