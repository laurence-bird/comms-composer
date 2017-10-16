package com.ovoenergy.comms.composer.rendering.pdf

import java.io.ByteArrayOutputStream

import com.ovoenergy.comms.composer.http.Retry
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.print.PrintInterpreter.PrintContext
import com.ovoenergy.comms.composer.print.{RenderedPrintHtml, RenderedPrintPdf}
import okhttp3._
import okio.{Buffer, BufferedSink, Okio}
import org.scalatest.{AppendedClues, EitherValues, FlatSpec, Matchers}

import scala.util.{Success, Try}

class DocRaptorClientSpec extends FlatSpec with Matchers with EitherValues with AppendedClues {

  val docRaptorConfig =
    DocRaptorConfig("yolo", "http://www.laurencelikestesting.com", true, Retry.RetryConfig(1, Retry.retryImmediately))
  val retryConfig = RetryConfig(1, Retry.retryImmediately)
  val renderedPrintHtml = RenderedPrintHtml("<html><h>Here is a template</h><body>Laurence says Hi</body></html>")

  it should "Return a RenderedPrintHtml for a successful response from DocRaptor" in {
    val response = "My cool pdf body".getBytes("UTF-8")
    val httpClient = httpClientWithResponse(200, response)

    val printContext = PrintContext(docRaptorConfig, null, retryConfig, null, httpClient)
    val renderedPdf = DocRaptorClient.renderPdf(printContext, renderedPrintHtml)

    renderedPdf shouldBe 'right
    renderedPdf.right.value.pdfBody should contain theSameElementsAs response
  }

  it should "Generate the correct error messages for a given status code" in {
    val errorResponse = "Oh no something went wrong!"

    val httpCodesAndExpectedResponses: Seq[(Int, DocRaptorError)] = Seq(
      (400, BadRequest(errorResponse)),
      (401, Unauthorised(errorResponse)),
      (403, Forbidden(errorResponse)),
      (422, UnprocessableEntity(errorResponse))
    )

    httpCodesAndExpectedResponses.foreach { codeAndResponse =>
      val httpClient = httpClientWithResponse(codeAndResponse._1, errorResponse.getBytes())
      val printContext = PrintContext(docRaptorConfig, null, retryConfig, null, httpClient)
      val renderedPdfEither = DocRaptorClient.renderPdf(printContext, renderedPrintHtml)
      renderedPdfEither.left.value shouldBe codeAndResponse._2
    }
  }

  it should "Not retry calls for the appropriate error codes" in {
    val noRetryStatusCodes = Seq(401, 422, 500)
    val bodyContent = "Its broken!".getBytes()
    val retryConfig = RetryConfig(5, Retry.retryImmediately)
    noRetryStatusCodes.foreach { code =>
      var totalCalls = 0

      val httpClient: (Request) => Try[Response] = {
        httpClientWithResponse(code, bodyContent).andThen { r =>
          totalCalls = totalCalls + 1
          r
        }
      }
      val printContext = PrintContext(docRaptorConfig, null, retryConfig, null, httpClient)
      DocRaptorClient.renderPdf(printContext, renderedPrintHtml)
      totalCalls shouldBe 1 withClue (s"for statusCode $code")
    }
  }

  it should "Retry for the appropriate error codes" in {
    val validRetryStatusCodes = Seq(400, 403)
    val bodyContent = "Docraptor is down".getBytes()
    val retryConfig = RetryConfig(5, Retry.retryImmediately)

    validRetryStatusCodes.foreach { code =>
      var totalCalls = 0

      val httpClient: (Request) => Try[Response] = {
        httpClientWithResponse(code, bodyContent).andThen { r =>
          totalCalls = totalCalls + 1
          r
        }
      }
      val printContext = PrintContext(docRaptorConfig, null, retryConfig, null, httpClient)
      DocRaptorClient.renderPdf(printContext, renderedPrintHtml)
      totalCalls shouldBe 5 withClue (s"for statusCode $code")
    }
  }

  it should "Generate the expected JSON to send to docRaptor" in {
    val expectedJson =
      """{"document_content":"<html><h>Here is a template</h><body>Laurence says Hi</body></html>","test":true,"type":"pdf","prince_options":{"profile":"PDF/X-1a:2003"},"javascript":true}"""

    val httpClientWithJsonAssertion = (req: Request) => {
      val out = new ByteArrayOutputStream
      val buffer = Okio.buffer(Okio.sink(out))
      req.body().writeTo(buffer)
      buffer.flush()
      val requestJson = out.toString("UTF-8")

      requestJson shouldBe expectedJson

      val response = new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .request(req)
        .code(200)
        .body(ResponseBody.create(MediaType.parse("UTF-8"), "hi"))
        .build()

      Success(response)
    }
    val printContext = PrintContext(docRaptorConfig, null, retryConfig, null, httpClientWithJsonAssertion)
    DocRaptorClient.renderPdf(printContext, renderedPrintHtml)
  }

  def httpClientWithResponse(statusCode: Int, bodyContent: Array[Byte]): (Request) => Try[Response] =
    (req: Request) => {
      val response = new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .request(req)
        .code(statusCode)
        .body(ResponseBody.create(MediaType.parse("UTF-8"), bodyContent))
        .build()

      Success(response)
    }
}
