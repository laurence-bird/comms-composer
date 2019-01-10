package com.ovoenergy.comms.composer
package http

import cats.effect.Sync
import org.http4s.{BuildInfo => _, _}
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import io.circe.literal._

class AdminRestApi[F[_]: Sync] extends Http4sDsl[F] {

  def adminService: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "ping" =>
      Ok()
    case GET -> Root / "health" =>
      Ok(json"""{"status": "healthy"}""")
    case GET -> Root / "info" =>
      Ok(json"""{"name": ${BuildInfo.name}, "version": ${BuildInfo.version}}""")

  }

}

object AdminRestApi {

  def apply[F[_]: Sync]: AdminRestApi[F] =
    new AdminRestApi[F]
}
