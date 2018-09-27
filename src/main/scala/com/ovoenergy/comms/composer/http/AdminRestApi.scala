package com.ovoenergy.comms.composer.http

import cats.Monad
import org.http4s.{HttpService, _}
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import io.circe.literal._

class AdminRestApi[F[_]: Monad] extends Http4sDsl[F] {

  def adminService: HttpService[F] = HttpService[F] {
    case GET -> Root / "health" =>
      Ok(json"""{"status": "healthy"}""")
  }

}

object AdminRestApi {

  def apply[F[_]: Monad]: AdminRestApi[F] =
    new AdminRestApi[F]
}
