package com.ovoenergy.comms.composer
package servicetest

import cats.implicits._
import cats.effect.IO
import org.http4s.Method._
import org.http4s.Status.Ok
import org.http4s.Uri
import org.http4s.syntax.all._
import org.http4s.client.Client
import org.http4s.client.blaze.Http1Client
import org.http4s.client.dsl.Http4sClientDsl

class HttpServiceSpec extends ServiceSpec with Http4sClientDsl[IO] {

  "Composer" should {
    "reply ok to the healthcheck" in {
      withHttpClient { client =>
        for {
          endpoint <- Uri.fromString(composerPublicEndpoint).fold(e => IO.raiseError(e), ok => ok.pure[IO])
          req <- GET(endpoint / "admin" / "health")
          status <- client.status(req)
        } yield status
      }.futureValue shouldBe Ok
    }
  }

}
