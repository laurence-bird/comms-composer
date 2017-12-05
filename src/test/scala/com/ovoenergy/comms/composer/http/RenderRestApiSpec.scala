package com.ovoenergy.comms.composer.http

import com.ovoenergy.comms.composer.{Interpreters, Logging}
import com.ovoenergy.comms.composer.Interpreters.FailedOr
import com.ovoenergy.comms.composer.http.RenderRestApi.RenderRequest
import com.ovoenergy.comms.composer.print.RenderedPrintPdf
import com.ovoenergy.comms.model._
import fs2.Task
import org.http4s._
import org.scalatest.{FlatSpec, Matchers}
import io.circe._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client._
import org.http4s.dsl._
import io.circe.generic.semiauto.deriveEncoder
import shapeless.{Inl, Inr}

class RenderRestApiSpec extends FlatSpec with Matchers {

  object TestHttpServce extends RenderRestApi with Logging

  def buildRenderPrint(response: FailedOr[RenderedPrintPdf]) = {
    (manifest: CommManifest, template: Map[String, TemplateData]) =>
      Task.now(response)
  }
  val successfulRender = buildRenderPrint(Right(RenderedPrintPdf("Hi".getBytes)))

  it should "return a valid response containing renderedPrintPDF if rendering is successful" in {
    import org.http4s.client._
    val renderRequest: RenderRequest = RenderRequest(
      Map("Foo" -> TemplateData.fromString("bar"))
    )

    val req: Task[Request] = POST(uri("/render/yolo/1.0/Service/print"), renderRequest.asJson)
    val service: HttpService = TestHttpServce.renderService(successfulRender)

    val response: Response = req.flatMap(service.run).unsafeRun().orNotFound
    response.status shouldBe Ok
  }

  it should "return an appropriate error if invalid comm type is passed in URL" in {
    import org.http4s.client._
    val renderRequest: RenderRequest = RenderRequest(
      Map("Foo" -> TemplateData.fromString("bar"))
    )

    val req: Task[Request] = POST(uri("/render/yolo/1.0/invalid/print"), renderRequest.asJson)
    val service: HttpService = TestHttpServce.renderService(successfulRender)

    val response: Response = req.flatMap(service.run).unsafeRun().orNotFound
    response.status shouldBe NotFound
  }

  it should "return an appropriate error if JSON deserialisation fails" in {
    import io.circe.parser._

    val invalidJson = parse("""{
      |"invalidBody": "yooo"
      |}
    """.stripMargin).right.get

    val req = POST(uri("/render/yolo/1.0/Service/print"), invalidJson)
    val service = TestHttpServce.renderService(successfulRender)

    val response = req.flatMap(service.run).unsafeRun().orNotFound
    response.status shouldBe BadRequest
  }

  it should "return an appropriate error if print rendering fails" in {

    val errorsAndExpectedResponses = List(
      (Interpreters.Error("Template download failed", TemplateDownloadFailed), Status.NotFound),
      (Interpreters.Error("Missing fields from template data: yo, lo", MissingTemplateData),
       Status.UnprocessableEntity),
      (Interpreters.Error("Missing fields from template data: yo, lo", CompositionError), Status.InternalServerError)
    )

    val renderRequest = RenderRequest(
      Map("Foo" -> TemplateData.fromString("bar"))
    )
    val req: Task[Request] = POST(uri("/render/yolo/1.0/Service/print"), renderRequest.asJson)

    errorsAndExpectedResponses.foreach { errs =>
      val error = errs._1
      val expectedResponse = errs._2
      val renderPrint = buildRenderPrint(Left(error))

      val service = TestHttpServce.renderService(renderPrint)
      val response = req.flatMap(service.run).unsafeRun().orNotFound
      response.status shouldBe expectedResponse
    }
  }

}
