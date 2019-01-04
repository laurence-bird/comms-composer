package com.ovoenergy.comms.composer
package http

import java.net.URI
import java.nio.charset.StandardCharsets

import http.RenderRestApi.{Render, RenderRequest}
import model.ComposerError
import model.Print.{PdfBody, RenderedPdf}
import com.ovoenergy.comms.model._
import io.circe.syntax._
import io.circe.parser._
import io.circe.literal._
import cats.implicits._
import cats.effect.IO
import org.http4s._
import org.http4s.implicits._
import org.http4s.circe._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl
import org.scalatest.{FlatSpec, Matchers}

class RenderRestApiSpec
    extends FlatSpec
    with Matchers
    with Http4sDsl[IO]
    with Http4sClientDsl[IO]
    with IOFutures {

  def buildRenderPrintF(response: IO[RenderedPdf]): Render[IO] = {
    (_: TemplateManifest, _: Map[String, TemplateData]) =>
      response
  }

  def buildRenderPrint(response: RenderedPdf): Render[IO] = {
    buildRenderPrintF(response.pure[IO])
  }

  def getBody(fields: Map[String, TemplateData]) =
    fs2.Stream
      .emit(RenderRequest(fields).asJson)
      .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
      .covary[IO]

  private val successfulRender = buildRenderPrint(RenderedPdf(PdfBody("Hi".getBytes)))

  it should "return a valid response containing renderedPrintPDF if rendering is successful" in {
    new RenderRestApi[IO](successfulRender).renderService.orNotFound
      .run(
        Request(
          method = Method.POST,
          uri = Uri.uri("/yolo/1.0/Service/print"),
          body = getBody(Map("Foo" -> TemplateData.fromString("bar")))
        ))
      .futureValue
      .status shouldBe Ok
  }

  it should "return an appropriate error if invalid comm type is passed in URL" in {
    new RenderRestApi[IO](successfulRender).renderService.orNotFound
      .run(
        Request(
          method = POST,
          uri = Uri.uri("/yolo/1.0/invalid/print"),
          body = getBody(Map("Foo" -> TemplateData.fromString("bar")))
        )
      )
      .futureValue
      .status shouldBe NotFound
  }

  it should "return an appropriate error if JSON deserialisation fails" in {
    new RenderRestApi[IO](successfulRender).renderService.orNotFound
      .run(
        Request(
          method = Method.POST,
          uri = Uri.uri("/yolo/1.0/Service/print"),
          body = fs2.Stream
            .emits("""{"invalidBody": "yooo"}""".getBytes(StandardCharsets.UTF_8).toSeq)
            .covary[IO]
        )
      )
      .futureValue
      .status shouldBe BadRequest
  }

  // TODO It should not really been NotFound if S3 is down for example
  it should "return NotFound if the template is missing" in {
    new RenderRestApi[IO](buildRenderPrintF(IO.raiseError(
      ComposerError("Template download failed", TemplateDownloadFailed)))).renderService.orNotFound
      .run(
        Request(
          method = Method.POST,
          uri = Uri.uri("/yolo/1.0/Service/print"),
          body = getBody(Map("Foo" -> TemplateData.fromString("bar")))
        )
      )
      .futureValue
      .status shouldBe NotFound
  }

  it should "return UnprocessableEntity if the template data is incomplete" in {
    new RenderRestApi[IO](
      buildRenderPrintF(
        IO.raiseError(ComposerError(
          "Missing fields from template data: yo, lo",
          MissingTemplateData)))).renderService.orNotFound
      .run(
        Request(
          method = Method.POST,
          uri = Uri.uri("/yolo/1.0/Service/print"),
          body = getBody(Map("Foo" -> TemplateData.fromString("bar")))
        )
      )
      .futureValue
      .status shouldBe UnprocessableEntity
  }

  it should "return InternalServerError if the template data is incomplete" in {
    new RenderRestApi[IO](buildRenderPrintF(IO.raiseError(
      ComposerError("Something really wrong", CompositionError)))).renderService.orNotFound
      .run(
        Request(
          method = Method.POST,
          uri = Uri.uri("/yolo/1.0/Service/print"),
          body = getBody(Map("Foo" -> TemplateData.fromString("bar")))
        )
      )
      .futureValue
      .status shouldBe InternalServerError
  }

}
