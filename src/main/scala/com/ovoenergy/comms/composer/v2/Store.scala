package com.ovoenergy.comms.composer
package v2

import org.http4s.Uri

trait Store[F[_]] {
  def upload(commId: CommId, traceToken: TraceToken, fragment: model.Fragment): F[Uri]
}
