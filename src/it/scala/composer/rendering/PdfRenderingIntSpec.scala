package com.ovoenergy.comms.composer
package rendering

import model.Print
import rendering.PdfRendering.DocRaptorConfig
import cats.effect.{IO, Timer}
import cats.implicits._
import fs2._
import ciris._
import cats.effect._
import ciris.credstash.credstashF
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.middleware.{RequestLogger, ResponseLogger}
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class PdfRenderingIntSpec extends IntegrationSpec {

  override implicit val patience: PatienceConfig = PatienceConfig(scaled(15.seconds), 500.millis)

  val docRaptorConfig: IO[DocRaptorConfig] = loadConfig(
    credstashF[IO, String]()("uat.docraptor.api_key")
  )(apiKey => DocRaptorConfig(apiKey, Uri.uri("https://docraptor.com"), isTest = true)).orRaiseThrowable


  "Pdf rendering" should {
    "render a well formatted HTML just fine" in {

      val body = Print.HtmlBody(
        """
          |<html>
          |  <head>
          |  </head>
          |  <body>
          |    <div>Hi there!</div>
          |  </body>
          |</html>
        """.stripMargin)

      withPdfRendering { pdfr =>
        pdfr.render(body, toWatermark = true)
      }.attempt.futureValue shouldBe a[Right[_,_]]
    }

  }

  def withPdfRendering[A](f: PdfRendering[IO] => IO[A]): IO[A] = {

    val ec = ExecutionContext.global
    implicit val contextShift = IO.contextShift(ec)
    implicit val timer: Timer[IO] = IO.timer(ec)

    BlazeClientBuilder[IO](ec)
      .stream
      .zip(Stream.eval(docRaptorConfig))
      .map {
        case (client, cfg) =>

          val responseLogger: Client[IO] => Client[IO] = ResponseLogger[IO](logBody = true, logHeaders = true)
          val requestLogger: Client[IO] => Client[IO] = RequestLogger[IO](logBody = false, logHeaders = true, redactHeadersWhen = _ => false)


          PdfRendering[IO](requestLogger(responseLogger(client)), cfg)
      }
      .evalMap(f)
      .compile
      .last
      .map(_.toRight[Throwable](new IllegalStateException("The stream should not be empty")))
      .rethrow
  }

}
