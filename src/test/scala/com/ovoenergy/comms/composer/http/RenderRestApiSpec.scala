package com.ovoenergy.comms.composer.http

import java.nio.charset.StandardCharsets

import io.circe.parser._
import cats.effect.{Async, IO}
import com.ovoenergy.comms.composer.{ComposerError, FailedOr, Logging}
import com.ovoenergy.comms.composer.http.RenderRestApi.RenderRequest
import com.ovoenergy.comms.composer.print.RenderedPrintPdf
import com.ovoenergy.comms.model._
import org.http4s._
import org.scalatest.{FlatSpec, Matchers}
import io.circe._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client._
import org.http4s.dsl.{io, _}
import shapeless.{Inl, Inr}

class RenderRestApiSpec extends FlatSpec with Matchers {

  object TestHttpServce extends RenderRestApi with Logging

  def buildRenderPrint(response: FailedOr[RenderedPrintPdf]) = {
    (manifest: TemplateManifest, template: Map[String, TemplateData]) =>
      Async[IO].pure(response)
  }

  val successfulRender = buildRenderPrint(Right(RenderedPrintPdf("Hi".getBytes)))

  val dsl = Http4sDsl[IO]
  import dsl._

  it should "return a valid response containing renderedPrintPDF if rendering is successful" in {
    val renderRequest: RenderRequest = RenderRequest(
      Map("Foo" -> TemplateData.fromString("bar"))
    )

    val req: Request[IO] = Request[IO](
      POST,
      uri("/render/yolo/1.0/Service/print"),
      body = fs2.Stream
        .emit(renderRequest.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    val service: HttpService[IO] = TestHttpServce.renderService[IO](successfulRender)

    val response: Response[IO] = service.orNotFound.run(req).unsafeRunSync()
    response.status shouldBe Ok
  }

  it should "return an appropriate error if invalid comm type is passed in URL" in {
    val renderRequest: RenderRequest = RenderRequest(
      Map("Foo" -> TemplateData.fromString("bar"))
    )

    val req: Request[IO] = Request[IO](
      POST,
      uri("/render/yolo/1.0/invalid/print"),
      body = fs2.Stream
        .emit(renderRequest.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    val service: HttpService[IO] = TestHttpServce.renderService[IO](successfulRender)

    val response: Response[IO] = service.orNotFound.run(req).unsafeRunSync()
    response.status shouldBe NotFound
  }

  it should "return an appropriate error if JSON deserialisation fails" in {

    val invalidJson = parse("""{
      |"invalidBody": "yooo"
      |}
    """.stripMargin).right.get

    val req: Request[IO] = Request[IO](
      POST,
      uri("/render/yolo/1.0/Service/print"),
      body = fs2.Stream
        .emit(invalidJson.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    val service: HttpService[IO] = TestHttpServce.renderService[IO](successfulRender)

    val response: Response[IO] = service.orNotFound.run(req).unsafeRunSync()
    response.status shouldBe BadRequest
  }

  it should "return an appropriate error if print rendering fails" in {

    val errorsAndExpectedResponses = List(
      (ComposerError("Template download failed", TemplateDownloadFailed), Status.NotFound),
      (ComposerError("Missing fields from template data: yo, lo", MissingTemplateData), Status.UnprocessableEntity),
      (ComposerError("Missing fields from template data: yo, lo", CompositionError), Status.InternalServerError)
    )

    val renderRequest = RenderRequest(
      Map("Foo" -> TemplateData.fromString("bar"))
    )

    val req: Request[IO] = Request[IO](
      POST,
      uri("/render/yolo/1.0/Service/print"),
      body = fs2.Stream
        .emit(renderRequest.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    errorsAndExpectedResponses.foreach { errs =>
      val error = errs._1
      val expectedResponse = errs._2
      val renderPrint = buildRenderPrint(Left(error))

      val service = TestHttpServce.renderService(renderPrint)
      val response = service.orNotFound.run(req).unsafeRunSync()
      response.status shouldBe expectedResponse
    }
  }

}
