package com.ovoenergy.comms.composer.rendering.pdf

import java.io._

import com.ovoenergy.comms.composer.http.{HttpClient, Retry}
import com.ovoenergy.comms.composer.print.{RenderedPrintHtml, RenderedPrintPdf}
import io.circe.Encoder
import cats.implicits._
import com.ovoenergy.comms.composer.Logging
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.print.PrintInterpreter.PrintContext
import io.circe.generic.auto._
import io.circe.syntax._
import okhttp3._

import scala.util.Try

case class DocRaptorRequest(
    document_content: String,
    test: Boolean,
    `type`: String,
    prince_options: PrinceOptions,
    javascript: Boolean = true) // We want to run JS assets prior to PDF rendering

case class PrinceOptions(profile: String)

case class DocRaptorConfig(apiKey: String, url: String, isTest: Boolean, retryConfig: RetryConfig)

sealed trait DocRaptorError {
  val httpError: String
  val errorDetails: String
  val shouldRetry: Boolean
}

// More details of docRaptor status codes at: https://docraptor.com/documentation/api#api_status_codes

case class BadRequest(errorDetails: String) extends DocRaptorError {
  val shouldRetry = true
  val httpError = "Bad Request"
}
case class UnknownError(errorDetails: String) extends DocRaptorError {
  val shouldRetry = false
  val httpError = "Unnkown error"
}
case class Unauthorised(errorDetails: String) extends DocRaptorError {
  val shouldRetry = false
  val httpError = "Unauthorised"
}
case class Forbidden(errorDetails: String) extends DocRaptorError {
  val shouldRetry = true
  val httpError = "Forbidden"
}
case class UnprocessableEntity(errorDetails: String) extends DocRaptorError {
  val shouldRetry = false
  val httpError = "Unprocessable Entity"
}

object DocRaptorClient extends Logging {

  def renderPdf(
      printContext: PrintContext,
      renderedPrintHtml: RenderedPrintHtml): Either[DocRaptorError, RenderedPrintPdf] = {

    val docRaptorConfig: DocRaptorConfig = printContext.docRaptorConfig
    val retryConfig: Retry.RetryConfig = printContext.retryConfig
    val httpClient: Request => Try[Response] = printContext.httpClient

    val req: DocRaptorRequest = DocRaptorRequest(
      renderedPrintHtml.htmlBody,
      docRaptorConfig.isTest,
      "pdf",
      PrinceOptions("PDF/X-1a:2003")
    )

    // Docraptor requires API key to be set as the username for basic Auth
    val credentials = Credentials.basic(docRaptorConfig.apiKey, "")

    val body = RequestBody.create(MediaType.parse("UTF-8"), req.asJson.noSpaces)

    def makeRequest = {
      val request = new Request.Builder()
        .header("Content-Type", "application/json")
        .header("Authorization", credentials)
        .url(docRaptorConfig.url + "/docs")
        .post(body)
        .build()

      log.info(s"Sending request to: ${docRaptorConfig.url}")
      httpClient.apply(request)
    }

    def handleApiResponse(response: Response): Either[DocRaptorError, RenderedPrintPdf] = {
      val responseBody = response.body()
      response.code match {
        case 200 => Right(RenderedPrintPdf(responseBody.bytes()))
        case 400 => Left(BadRequest(responseBody.string()))
        case 401 => Left(Unauthorised(responseBody.string()))
        case 403 => Left(Forbidden(responseBody.string()))
        case 422 => Left(UnprocessableEntity(responseBody.string()))
        case otherStatusCode =>
          Left(UnknownError(
            s"Request to DocRaptor failed with unknown response, statusCode ${otherStatusCode}, response ${responseBody
              .string()}"))
      }
    }

    val onFailure = (error: DocRaptorError) =>
      log.warn(s"Request to docraptor failed with response: ${error.errorDetails}")

    val result =
      Retry.retry[DocRaptorError, RenderedPrintPdf](retryConfig, onFailure, _.shouldRetry) {
        makeRequest.toEither
          .leftMap((e: Throwable) =>
            UnknownError(s"Call to Docraptor failed with error: ${e.getMessage}"))
          .flatMap(handleApiResponse)
      }

    result.flattenRetry
  }
}
