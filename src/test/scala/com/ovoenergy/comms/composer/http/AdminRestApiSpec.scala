package com.ovoenergy.comms.composer
package http

import cats.effect.IO

import org.scalatest.{FlatSpec, Matchers}

import org.http4s._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl

class AdminRestApiSpec
    extends FlatSpec
    with Matchers
    with IOFutures
    with Http4sDsl[IO]
    with Http4sClientDsl[IO] {

  private val service = AdminRestApi[IO].adminService.orNotFound

  "/health" should "return HTTP 200 when the service is healthy" in {
    val response = (for {
      request <- GET(Uri.uri("/health"))
      response <- service.run(request)
    } yield response).futureValue

    response.status shouldBe Ok
  }

  "/foo" should "return HTTP 404" in {
    val response = (for {
      request <- GET(Uri.uri("/foo"))
      response <- service.run(request)
    } yield response).futureValue

    response.status shouldBe NotFound
  }

}
