package com.ovoenergy.comms.composer
package rendering

import org.http4s.client.blaze.Http1Client
import cats.effect.IO
import cats.implicits._
import com.ovoenergy.comms.composer.v2.rendering.PdfRendering.DocRaptorConfig
import fs2._
import ciris._
import ciris.syntax._
import ciris.cats.effect._
import ciris.credstash.credstashF
import model.Print
import org.http4s.client.Client
import org.http4s.client.middleware.{ResponseLogger, RequestLogger}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class PdfRenderingIntSpec extends IntegrationSpec {

  override implicit val patience: PatienceConfig = PatienceConfig(scaled(15.seconds), 500.millis)

  val docRaptorConfig: IO[DocRaptorConfig] = loadConfig(
    credstashF[IO, String]()("prd.docraptor.api_key")
  )(apiKey => DocRaptorConfig(apiKey, "https://docraptor.com", isTest = true)).orRaiseThrowable


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
        pdfr.render(body)
      }.attempt.futureValue shouldBe a[Right[_,_]]
    }

  }

  def withPdfRendering[A](f: PdfRendering[IO] => IO[A]): IO[A] = {
    Scheduler[IO](2)
      .flatMap { implicit sch =>
        Http1Client
          .stream[IO]()
          .zip(Stream.eval(docRaptorConfig))
          .map {
            case (client, cfg) =>

              val responseLogger: Client[IO] => Client[IO] = ResponseLogger.apply0[IO](logBody = true, logHeaders = true)
              val requestLogger: Client[IO] => Client[IO] = RequestLogger.apply0[IO](logBody = false, logHeaders = true, redactHeadersWhen = _ => false)


              PdfRendering[IO](requestLogger(responseLogger(client)), cfg)
          }
          .evalMap(f)
      }
      .compile
      .last
      .map(_.toRight[Throwable](new IllegalStateException("The stream should not be empty")))
      .rethrow
  }

}
