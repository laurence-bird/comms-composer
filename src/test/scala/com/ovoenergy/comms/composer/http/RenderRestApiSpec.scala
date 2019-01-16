package com.ovoenergy.comms.composer
package http

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

import com.ovoenergy.comms.model._

import RenderRestApi.{Render, RenderRequest}
import model.ComposerError
import model.Print.{RenderedPdf, PdfBody}

class RenderRestApiSpec extends UnitSpec with Http4sDsl[IO] with Http4sClientDsl[IO] {

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

    val service = new RenderRestApi[IO](successfulRender).renderService.orNotFound

    (for {
      request <- POST(
        RenderRequest(Map("Foo" -> TemplateData.fromString("bar"))).asJson,
        Uri.uri("/yolo/1.0/Service/print"))
      response <- service.run(request)
    } yield response.status).futureValue shouldBe Ok

  }

  it should "return an appropriate error if invalid comm type is passed in URL" in {
    val service = new RenderRestApi[IO](successfulRender).renderService.orNotFound
    (for {
      request <- POST(
        RenderRequest(Map("Foo" -> TemplateData.fromString("bar"))).asJson,
        Uri.uri("/yolo/1.0/invalid/print"))
      response <- service.run(request)
    } yield response.status).futureValue shouldBe NotFound
  }

  it should "return an appropriate error if JSON deserialisation fails" in {
    val service = new RenderRestApi[IO](successfulRender).renderService.orNotFound
    (for {
      request <- POST(json"""{"invalidBody": "yooo"}""", Uri.uri("/yolo/1.0/Service/print"))
      response <- service.run(request)
    } yield response.status).futureValue shouldBe BadRequest
  }

  // TODO It should not really been NotFound if S3 is down for example
  it should "return NotFound if the template is missing" in {
    val service = new RenderRestApi[IO](
      buildRenderPrintF(IO.raiseError(
        ComposerError("Template download failed", TemplateDownloadFailed)))).renderService
      .orNotFound
    (for {
      request <- POST(
        RenderRequest(Map("Foo" -> TemplateData.fromString("bar"))).asJson,
        Uri.uri("/yolo/1.0/Service/print"))
      response <- service.run(request)
    } yield response.status).futureValue shouldBe NotFound
  }

  it should "return UnprocessableEntity if the template data is incomplete" in {
    val service = new RenderRestApi[IO](
      buildRenderPrintF(
        IO.raiseError(ComposerError(
          "Missing fields from template data: yo, lo",
          MissingTemplateData)))).renderService.orNotFound
    (for {
      request <- POST(
        RenderRequest(Map("Foo" -> TemplateData.fromString("bar"))).asJson,
        Uri.uri("/yolo/1.0/Service/print"))
      response <- service.run(request)
    } yield response.status).futureValue shouldBe UnprocessableEntity
  }

  it should "return InternalServerError if the template data is incomplete" in {
    val service = new RenderRestApi[IO](buildRenderPrintF(
      IO.raiseError(ComposerError("Something really wrong", CompositionError)))).renderService
      .orNotFound

    (for {
      request <- POST(
        RenderRequest(Map("Foo" -> TemplateData.fromString("bar"))).asJson,
        Uri.uri("/yolo/1.0/Service/print"))
      response <- service.run(request)
    } yield response.status).futureValue shouldBe InternalServerError
  }

}
