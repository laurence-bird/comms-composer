package com.ovoenergy.comms.composer
package v2

import cats.effect.Effect
import cats.implicits._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import fs2._
import model.Print
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.{BasicCredentials, Request, Response, Uri}
import org.http4s.client.Client
import org.http4s.Method._
import org.http4s.circe._
import org.http4s.client.middleware.{RetryPolicy, Retry}
import org.http4s.headers._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait PdfRendering[F[_]] {
  def render(renderedPrintHtml: Print.HtmlBody): F[Print.RenderedPdf]
}

object PdfRendering extends Logging {

  case class DocRaptorRequest(
      document_content: String,
      test: Boolean,
      `type`: String,
      prince_options: PrinceOptions,
      javascript: Boolean = true) // We want to run JS assets prior to PDF rendering

  case class PrinceOptions(profile: String)

  case class DocRaptorConfig(apiKey: String, url: String, isTest: Boolean)

  sealed trait DocRaptorError extends Throwable {
    val errorDetails: String
  }

  trait Retriable extends DocRaptorError

  // More details of docRaptor status codes at: https://docraptor.com/documentation/api#api_status_codes
  case class BadRequest(errorDetails: String) extends Retriable
  case class UnknownError(errorDetails: String) extends DocRaptorError
  case class Unauthorised(errorDetails: String) extends DocRaptorError
  case class Forbidden(errorDetails: String) extends Retriable
  case class UnProcessableEntity(errorDetails: String) extends DocRaptorError

  def apply[F[_]](client: Client[F], docRaptorConfig: DocRaptorConfig)(
      implicit ec: ExecutionContext,
      F: Effect[F],
      s: Scheduler): PdfRendering[F] with Http4sClientDsl[F] = {

    def retriable(req: Request[F], result: Either[Throwable, Response[F]]): Boolean = {
      result match {
        case Right(_) => false
        case Left(err) => err.isInstanceOf[Retriable]
      }
    }

    val retryingClient =
      Retry[F](RetryPolicy(RetryPolicy.exponentialBackoff(2 minutes, Int.MaxValue), retriable))(
        client)

    new PdfRendering[F] with Http4sClientDsl[F] {

      implicit val docRaptorRequestEncoder = deriveEncoder[DocRaptorRequest]
      implicit val docRaptorRequestEntityEncoder = jsonEncoderOf[F, DocRaptorRequest]

      def render(renderedPrintHtml: Print.HtmlBody): F[Print.RenderedPdf] = {

        val body: DocRaptorRequest = DocRaptorRequest(
          renderedPrintHtml.htmlBody,
          docRaptorConfig.isTest,
          "pdf",
          PrinceOptions("PDF/X-1a:2003")
        )
        // Docraptor requires API key to be set as the username for basic Auth
        val credentials = BasicCredentials(docRaptorConfig.apiKey, "")

        for {
          uri <- F.fromEither(Uri.fromString(s"${docRaptorConfig.url}/docs"))
          request <- POST(uri, body, Authorization(credentials))
          result <- retryingClient.expectOr[Array[Byte]](request) { response =>
            response
              .as[String]
              .map(error =>
                response.status.code match {
                  case 400 => BadRequest(error)
                  case 401 => Unauthorised(error)
                  case 403 => Forbidden(error)
                  case 422 => UnProcessableEntity(error)
                  case otherStatusCode =>
                    UnknownError(
                      s"Request to DocRaptor failed with unknown response, statusCode ${otherStatusCode}, response $error")
              })
          }
        } yield Print.RenderedPdf(Print.PdfBody(result))
      }
    }
  }
}
