package com.ovoenergy.comms.composer.http

import cats.data.OptionT
import cats.effect.IO
import org.scalatest.{FlatSpec, Matchers}
import org.http4s.dsl._
import org.http4s._

class AdminRestApiSpec extends FlatSpec with Matchers with AdminRestApi {

  val dsl = Http4sDsl[IO]
  import dsl._

  import org.http4s.circe._

  "admin/health" should "return HTTP 200 when the service is healthy" in {
    val response = adminService.run(Request(GET, Uri.unsafeFromString("/admin/health"))).unsafeRun().orNotFound

    response.status shouldBe Ok
  }

}
