package com.ovoenergy.comms.composer
package rendering

import scala.concurrent.ExecutionContext

import cats.effect.{IO, Timer}
import fs2._

import org.scalatest._

import org.http4s._
import org.http4s.implicits._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`WWW-Authenticate`
import org.scalatest.concurrent.ScalaFutures

// import com.ovoenergy.comms.composer.IOFutures
// import com.ovoenergy.comms.composer.model.Print
// import com.ovoenergy.comms.composer.rendering.PdfRendering.DocRaptorConfig

// import model.Print
// import PdfRendering.DocRaptorConfig

class PdfRenderingSpec
    extends UnitSpec
    with EitherValues
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalaFutures
    with TryValues
    with Assertions {

  // behavior of "Pdf Renderer"

  // val uri = Uri.unsafeFromString(s"http://localhost:8080")
  // val docRaptorConfig = DocRaptorConfig("apiKey", uri, true)

  // val printBody =
  //   Print.HtmlBody("<html><h>Here is a template</h><body>Laurence says Hi</body></html>")
  // val renderedBody = printBody.htmlBody.getBytes

  // val dsl = Http4sDsl[IO]
  // import dsl._

  // implicit val ec = ExecutionContext.global
  // implicit val contextShift = cats.effect.IO.contextShift(ec)
  // implicit val timer: Timer[IO] = IO.timer(ec)

  // it should "successfully render rendered print html" in {
  //   val httpClient = Client.fromHttpApp(
  //     HttpRoutes
  //       .of[IO] {
  //         case POST -> Root / "docs" => Ok(renderedBody)
  //       }
  //       .orNotFound)

  //   PdfRendering(httpClient, docRaptorConfig)
  //     .render(printBody, false)
  //     .eitherValue
  //     .map(_.map(result => new String(result.fragment.content) shouldBe printBody.htmlBody))
  // }

  // it should "fail when receiving bad request status" in {
  //   val httpClient = Client.fromHttpApp(
  //     HttpRoutes
  //       .of[IO] {
  //         case POST -> Root / "docs" => BadRequest()
  //       }
  //       .orNotFound)

  //   val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.BadRequest]
  //   testFailure(httpClient, assertion)
  // }

  // it should "fail when receiving unauthorized status" in {
  //   val httpClient = Client.fromHttpApp(
  //     HttpRoutes
  //       .of[IO] {
  //         case POST -> Root / "docs" =>
  //           Unauthorized.apply(
  //             `WWW-Authenticate`(Challenge("scheme", "realm", Map.empty[String, String])))
  //       }
  //       .orNotFound)

  //   val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.Unauthorised]
  //   testFailure(httpClient, assertion)
  // }

  // it should "fail when receiving unknown error" in {
  //   val httpClient = Client.fromHttpApp(
  //     HttpRoutes
  //       .of[IO] {
  //         case POST -> Root / "docs" =>
  //           InternalServerError()
  //       }
  //       .orNotFound)

  //   val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.UnknownError]
  //   testFailure(httpClient, assertion)
  // }

  // it should "fail when receiving forbidden status" in {
  //   val httpClient = Client.fromHttpApp(
  //     HttpRoutes
  //       .of[IO] {
  //         case POST -> Root / "docs" => Forbidden()
  //       }
  //       .orNotFound)

  //   val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.Forbidden]
  //   testFailure(httpClient, assertion)
  // }

  // it should "fail when receiving unprocessableEntity status" in {
  //   val httpClient = Client.fromHttpApp(
  //     HttpRoutes
  //       .of[IO] {
  //         case POST -> Root / "docs" => UnprocessableEntity()
  //       }
  //       .orNotFound)

  //   val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.UnProcessableEntity]
  //   testFailure(httpClient, assertion)
  // }

  // def testFailure(httpClient: Client[IO], test: Throwable => Assertion) = {
  //   whenReady {
  //     PdfRendering(httpClient, docRaptorConfig)
  //       .render(printBody, false)
  //       .unsafeToFuture
  //       .failed
  //   }(test)
  // }

  // override protected def afterAll(): Unit = {
  //   super.afterAll()
  // }

}
