package com.ovoenergy.comms.composer
package http

import cats.effect.IO

import org.http4s._
import org.http4s.implicits._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl

class AdminRestApiSpec extends UnitSpec with Http4sDsl[IO] with Http4sClientDsl[IO] {

  private val service = AdminRestApi[IO].adminService

  "/health" should "return HTTP 200 when the service is healthy" in {
    service.orNotFound
      .run(Request(method = Method.GET, uri = Uri.uri("/health")))
      .futureValue
      .status shouldBe Ok
  }

  "/foo" should "return HTTP 404" in {
    service.orNotFound
      .run(Request(method = Method.GET, uri = Uri.uri("/foo")))
      .futureValue
      .status shouldBe NotFound
  }

}
