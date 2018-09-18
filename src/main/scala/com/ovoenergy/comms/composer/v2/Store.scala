package com.ovoenergy.comms.composer
package v2

import org.http4s.Uri
import model.Fragment

trait Store[F[_]] {
  def upload[A: Fragment](commId: CommId, traceToken: TraceToken, fragment: A): F[Uri]
}
