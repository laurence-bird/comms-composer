package com.ovoenergy.comms.composer.http

import cats.effect.Sync
import com.ovoenergy.comms.composer.http.RenderRestApi._
import com.ovoenergy.comms.model._
import io.circe.{Json, Encoder, Decoder}
import org.http4s.{Request, Response, HttpService}
import org.http4s.dsl.Http4sDsl
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.implicits._
import com.ovoenergy.comms.composer.{Logging, Time, Templates}
import com.ovoenergy.comms.composer.model.Print.RenderedPdf
import com.ovoenergy.comms.composer.model._
import com.ovoenergy.comms.composer.rendering.Rendering
import com.ovoenergy.comms.templates.util.Hash
import shapeless.{Inl, Inr}
import org.http4s.circe._

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

  // TODO Use select
  implicit def templateDataCirceEncoder: Encoder[TemplateData] = Encoder.instance {
    case TemplateData(Inl(value)) => Json.fromString(value)
    case TemplateData(Inr(Inl(value))) =>
      Json.fromValues(value.map(x => Json.fromString(x.value.toString)))
    case TemplateData(Inr(Inr(Inl(value: Map[String, TemplateData])))) => {
      Json.obj(value.mapValues(_.asJson).toSeq: _*)
    }
    case _ =>
      throw new IllegalArgumentException
  }

  implicit val renderRequestEncoder: Encoder[RenderRequest] = deriveEncoder[RenderRequest]

  implicit val renderRequestDecoder: Decoder[RenderRequest] = deriveDecoder[RenderRequest]

  case class RenderResponse(renderedPrint: RenderedPdf)

  implicit def renderResponseEncoder: Encoder[RenderResponse] = deriveEncoder[RenderResponse]

  implicit def renderResponseDecoder: Decoder[RenderResponse] = deriveDecoder[RenderResponse]

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

  def apply[F[_]: Sync](
      render: (TemplateManifest, Map[String, TemplateData]) => F[RenderedPdf]): RenderRestApi[F] =
    new RenderRestApi[F](render) with Logging
}

class RenderRestApi[F[_]: Sync](
    render: (TemplateManifest, Map[String, TemplateData]) => F[RenderedPdf])
    extends Http4sDsl[F] { logger: Logging =>

  def renderService: HttpService[F] = HttpService[F] {

    case req @ POST -> Root / CommNamePath(commName) / CommVersionPath(commVersion) / CommTypePath(
          commType) / "print" =>
      deserialiseRequest(req).flatMap {
        case Left(err) => BadRequest(err)
        case Right(r: RenderRequest) =>
          handleRenderRequest(r, TemplateManifest(Hash(commName.value), commVersion.value))
      }
    case req @ POST -> Root / TemplateIdPath(templateId) / TemplateVersionPath(templateVersion) / "print" =>
      deserialiseRequest(req).flatMap {
        case Left(err) => BadRequest(err)
        case Right(r: RenderRequest) =>
          handleRenderRequest(r, TemplateManifest(templateId.value, templateVersion.value))
      }

  }

  private def handleRenderRequest(
      renderRequest: RenderRequest,
      templateManifest: TemplateManifest): F[Response[F]] =
    for {
      renderedPrint <- render(templateManifest, renderRequest.data).attempt
      response <- buildApiResponse(renderedPrint)
    } yield response

  private def deserialiseRequest(req: Request[F]): F[Either[String, RenderRequest]] =
    req
      .decodeJson[RenderRequest]
      .attempt
      .map { requestAttempt =>
        requestAttempt.left.map { a =>
          s"Failed to deserialise request body: ${a.getMessage}"
        }
      }

  private def buildApiResponse(renderResult: Either[Throwable, RenderedPdf]): F[Response[F]] = {

    def handleError(error: ComposerError): F[Response[F]] = {
      error.errorCode match {
        case TemplateDownloadFailed => NotFound(ErrorResponse(error.reason).asJson)
        case MissingTemplateData => UnprocessableEntity(ErrorResponse(error.reason).asJson)
        case _ => InternalServerError(ErrorResponse(error.reason).asJson)

      }
    }

    renderResult match {
      case Left(err: ComposerError) => handleError(err)
      case Left(err) => handleError(ComposerError(err.getMessage, CompositionError))
      case Right(r) => Ok(RenderResponse(r).asJson)
    }
  }
}
