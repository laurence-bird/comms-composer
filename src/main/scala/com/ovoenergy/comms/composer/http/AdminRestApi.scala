package com.ovoenergy.comms.composer.http

import cats.effect.Effect
import org.http4s.{HttpService, _}
import org.http4s.dsl.Http4sDsl
import io.circe.literal._

trait AdminRestApi {

  def adminService[F[_]: Effect]: HttpService[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    import org.http4s.circe._

    HttpService[F] {
      case GET -> Root / "admin" / "health" =>
        Ok(json"""{"status": "healthy"}""")
    }

  }
}
