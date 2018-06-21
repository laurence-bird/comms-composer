package com.ovoenergy.comms.composer.http

import java.util.Base64

import cats.effect.Effect
import com.ovoenergy.comms.composer.http.RenderRestApi._
import com.ovoenergy.comms.composer.print.RenderedPrintPdf
import com.ovoenergy.comms.model._
import io.circe.{Decoder, Encoder, Json}
import org.http4s.{EntityDecoder, HttpService, Message, Request, Response, Status}
import org.http4s.dsl.Http4sDsl
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.implicits._
import com.ovoenergy.comms.composer.{ComposerError, FailedOr, Logging}
import com.ovoenergy.comms.templates.util.Hash
import shapeless.{Inl, Inr}
import org.http4s.circe._
import org.http4s.dsl._

case class CommName(value: String) extends AnyVal

case class CommVersion(value: String) extends AnyVal

case class TemplateId(value: String) extends AnyVal

case class TemplateVersion(value: String) extends AnyVal

object RenderRestApi {

  case class RenderRequest(data: Map[String, TemplateData])

  implicit def templateDataCirceDecoder: Decoder[TemplateData] = Decoder.instance { hc =>
    hc.value
      .fold(
        Right(TemplateData.fromString("")),
        b => Right(TemplateData.fromString(b.toString)),
        n => Right(TemplateData.fromString(n.toString)),
        s => Right(TemplateData.fromString(s)),
        xs => {
          xs.map(_.as[TemplateData])
            .sequence
            .map(TemplateData.fromSeq)
        },
        obj => {
          obj.toMap
            .map {
              case (key, value) =>
                value.as[TemplateData].map(key -> _)
            }
            .toVector
            .sequence
            .map(x => TemplateData.fromMap(x.toMap))
        }
      )
  }

  implicit def templateDataCirceEncoder: Encoder[TemplateData] = Encoder.instance {
    case TemplateData(Inl(value)) => Json.fromString(value)
    case TemplateData(Inr(Inl(value))) => Json.fromValues(value.map(x => Json.fromString(x.value.toString)))
    case TemplateData(Inr(Inr(Inl(value: Map[String, TemplateData])))) => {
      Json.obj(value.mapValues(_.asJson).toSeq: _*)
    }
  }

  implicit val renderRequestEncoder: Encoder[RenderRequest] = deriveEncoder[RenderRequest]

  implicit val renderRequestDecoder: Decoder[RenderRequest] = deriveDecoder[RenderRequest]

  case class RenderResponse(renderedPrint: RenderedPrintPdf)

  implicit def renderResponseEncoder: Encoder[RenderResponse] = deriveEncoder[RenderResponse]

  implicit def renderResponseDecoder: Decoder[RenderResponse] = deriveDecoder[RenderResponse]

  implicit def renderedPrintPdfCirceEncoder: Encoder[RenderedPrintPdf] =
    Encoder.encodeString.contramap[RenderedPrintPdf](x => Base64.getEncoder.encodeToString(x.pdfBody))

  object CommNamePath {
    def unapply(str: String): Option[CommName] = {
      Some(CommName(str))
    }
  }

  object CommVersionPath {
    def unapply(str: String): Option[CommVersion] = {
      Some(CommVersion(str))
    }
  }

  object TemplateIdPath {
    def unapply(str: String): Option[TemplateId] = {
      Some(TemplateId(str))
    }
  }

  object TemplateVersionPath {
    def unapply(str: String): Option[TemplateVersion] = {
      Some(TemplateVersion(str))
    }
  }

  // TODO: Make case insensitive (fromStringCaseInsensitive)
  object CommTypePath {
    def unapply(str: String): Option[CommType] = {
      CommType.fromString(str)
    }
  }

  case class ErrorResponse(message: String)
  implicit def errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder[ErrorResponse]
  implicit def errorResponseDecoder: Decoder[ErrorResponse] = deriveDecoder[ErrorResponse]

}

trait RenderRestApi { logger: Logging =>

  def renderService[F[_]: Effect](
      renderPrint: (TemplateManifest, Map[String, TemplateData]) => F[FailedOr[RenderedPrintPdf]]): HttpService[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    def handleRenderRequest(renderRequest: RenderRequest, templateManifest: TemplateManifest) =
      for {
        renderedPrint <- renderPrint(templateManifest, renderRequest.data)
        response <- buildApiResponse(renderedPrint)
      } yield response

    HttpService[F] {
      case req @ POST -> Root / "render" / CommNamePath(commName) / CommVersionPath(commVersion) / CommTypePath(
            commType) / "print" => {

        deserialiseRequest[F](req).flatMap {
          case Left(err) => BadRequest(err)
          case Right(r: RenderRequest) =>
            handleRenderRequest(r, TemplateManifest(Hash(commName.value), commVersion.value))
        }
      }
      case req @ POST -> Root / "render" / TemplateIdPath(templateId) / TemplateVersionPath(templateVersion) / "print" => {

        deserialiseRequest[F](req).flatMap {
          case Left(err) => BadRequest(err)
          case Right(r: RenderRequest) =>
            handleRenderRequest(r, TemplateManifest(templateId.value, templateVersion.value))
        }
      }
    }
  }

  private def deserialiseRequest[F[_]: Effect](req: Request[F]): F[Either[String, RenderRequest]] =
    req
      .decodeJson[RenderRequest]
      .attempt
      .map { requestAttempt =>
        requestAttempt.left.map { a =>
          log.warn(s"Failed to deserialise request: ${a.getMessage}")
          s"Failed to deserialise request body: ${a.getMessage}"
        }
      }

  private def buildApiResponse[F[_]: Effect](renderResult: FailedOr[RenderedPrintPdf]): F[Response[F]] = {

    val dsl = Http4sDsl[F]
    import dsl._

    def handleError(error: ComposerError): F[Response[F]] = {
      error.errorCode match {
        case TemplateDownloadFailed => NotFound(ErrorResponse(error.reason).asJson)
        case MissingTemplateData => UnprocessableEntity(ErrorResponse(error.reason).asJson)
        case _ => InternalServerError(ErrorResponse(error.reason).asJson)

      }
    }

    renderResult match {
      case Left(err) => handleError(err)
      case Right(r) => Ok(RenderResponse(r).asJson)
    }
  }
}
