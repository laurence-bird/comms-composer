package com.ovoenergy.comms.composer.rendering

import fs2._
import cats.effect.IO
import com.ovoenergy.comms.composer.IOFutures
import com.ovoenergy.comms.composer.model.Print
import com.ovoenergy.comms.composer.rendering.PdfRendering.DocRaptorConfig
import org.http4s.client.Client
import org.scalatest._
import org.http4s.{Challenge, Uri, HttpService}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`WWW-Authenticate`
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext.Implicits.global

class PdfRenderingSpec
    extends FlatSpec
    with Matchers
    with EitherValues
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalaFutures
    with IOFutures
    with TryValues
    with Assertions {

  behavior of "Pdf Renderer"

  val uri = Uri.unsafeFromString(s"http://localhost:8080")
  val docRaptorConfig = DocRaptorConfig("apiKey", uri, true)

  val printBody =
    Print.HtmlBody("<html><h>Here is a template</h><body>Laurence says Hi</body></html>")
  val renderedBody = printBody.htmlBody.getBytes

  val dsl = Http4sDsl[IO]
  import dsl._

  implicit val (scheduler, shutdown) = Scheduler.allocate[IO](1).unsafeRunSync()

  it should "successfully render rendered print html" in {
    val httpClient = Client.fromHttpService(HttpService[IO] {
      case POST -> Root / "docs" => Ok(renderedBody)
    })

    PdfRendering(httpClient, docRaptorConfig)
      .render(printBody)
      .eitherValue
      .map(_.map(result => new String(result.fragment.content) shouldBe printBody.htmlBody))
  }

  it should "fail when receiving bad request status" in {
    val httpClient = Client.fromHttpService(HttpService[IO] {
      case POST -> Root / "docs" => BadRequest()
    })

    val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.BadRequest]
    testFailure(httpClient, assertion)
  }

  it should "fail when receiving unauthorized status" in {
    val httpClient = Client.fromHttpService(HttpService[IO] {
      case POST -> Root / "docs" =>
        Unauthorized.apply(
          `WWW-Authenticate`(Challenge("scheme", "realm", Map.empty[String, String])))
    })

    val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.Unauthorised]
    testFailure(httpClient, assertion)
  }

  it should "fail when receiving unknown error" in {
    val httpClient = Client.fromHttpService(HttpService[IO] {
      case POST -> Root / "docs" =>
        InternalServerError()
    })

    val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.UnknownError]
    testFailure(httpClient, assertion)
  }

  it should "fail when receiving forbidden status" in {
    val httpClient = Client.fromHttpService(HttpService[IO] {
      case POST -> Root / "docs" => Forbidden()
    })

    val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.Forbidden]
    testFailure(httpClient, assertion)
  }

  it should "fail when receiving unprocessableEntity status" in {
    val httpClient = Client.fromHttpService(HttpService[IO] {
      case POST -> Root / "docs" => UnprocessableEntity()
    })

    val assertion = (throwable: Throwable) => throwable shouldBe a[PdfRendering.UnProcessableEntity]
    testFailure(httpClient, assertion)
  }

  def testFailure(httpClient: Client[IO], test: Throwable => Assertion) = {
    whenReady {
      PdfRendering(httpClient, docRaptorConfig)
        .render(printBody)
        .unsafeToFuture
        .failed
    }(test)
  }

  override protected def afterAll(): Unit = {
    shutdown.unsafeRunSync()
    super.afterAll()
  }

}
