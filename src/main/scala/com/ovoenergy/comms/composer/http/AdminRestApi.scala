package com.ovoenergy.comms.composer.http

import org.http4s.HttpService
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe._
import io.circe.literal._

trait AdminRestApi {

  def adminService: HttpService = HttpService {
    case GET -> Root / "admin" / "health" =>
      Ok(json"""{"status": "healthy"}""")
  }

}
