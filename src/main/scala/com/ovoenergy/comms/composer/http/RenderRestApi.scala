package com.ovoenergy.comms.composer.http

import java.time.LocalDate
import java.util.Base64

import com.ovoenergy.comms.composer.http.RenderRestApi._
import com.ovoenergy.comms.composer.print.{PrintComposer, PrintComposerA, RenderedPrintPdf}
import com.ovoenergy.comms.model._
import fs2.Task
import org.http4s.{HttpService, Request, Response}
import org.http4s.dsl._
import org.http4s.circe._
import io.circe.literal._
import org.http4s.multipart.Multipart
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.implicits._
import cats.~>
import com.ovoenergy.comms.composer.Interpreters
import com.ovoenergy.comms.composer.Interpreters.FailedOr
import fs2.util.Attempt

case class CommName(value: String) extends AnyVal

case class CommVersion(value: String) extends AnyVal

object RenderRestApi {

  case class RenderRequest(data: Map[String, TemplateData])

  case class RenderResponse(renderedPrint: RenderedPrintPdf)

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

  // TODO: Make case insensitive (fromStringCaseInsensitive)
  object CommTypePath {
    def unapply(str: String): Option[CommType] = {
      CommType.fromString(str)
    }
  }

  implicit def renderResponseCirceEncoder: Encoder[RenderResponse] = deriveEncoder[RenderResponse]

  implicit def renderedPrintPdfCirceEncoder: Encoder[RenderedPrintPdf] =
    Encoder.encodeString.contramap[RenderedPrintPdf](x => Base64.getEncoder.encodeToString(x.pdfBody))

  implicit def templateDataCirceDecoder: Decoder[TemplateData] = Decoder.instance { hc =>
    hc.value
      .fold(
        Right(TemplateData.fromString("")),
        b => Right(TemplateData.fromString(b.toString)),
        n => Right(TemplateData.fromString(n.toString)),
        s => Right(TemplateData.fromString(s)),
        xs => {
          xs.map(_.as[TemplateData])
            .sequenceU
            .map(TemplateData.fromSeq)
        },
        obj => {
          obj.toMap
            .map {
              case (key, value) =>
                value.as[TemplateData].map(key -> _)
            }
            .toVector
            .sequenceU
            .map(x => TemplateData.fromMap(x.toMap))
        }
      )
  }

  implicit def renderRequest: Decoder[RenderRequest] = deriveDecoder[RenderRequest]
}

trait RenderRestApi {

  def render(renderPrint: (CommManifest, Map[String, TemplateData]) => Task[FailedOr[RenderedPrintPdf]]): HttpService = {
    def handleRenderRequest(renderRequest: RenderRequest, commManifest: CommManifest) = {
      for {
        renderedPrint <- renderPrint(commManifest, renderRequest.data)
        response <- buildApiResponse(renderedPrint)
      } yield response
    }

    HttpService {
      case req @ POST -> Root / "render" / CommNamePath(commName) / CommVersionPath(commVersion) / CommTypePath(
            commType) / "print" => {

        deserialiseRequest(req).flatMap {
          case Left(err) => BadRequest(err)
          case Right(r) => handleRenderRequest(r, CommManifest(commType, commName.value, commVersion.value))
        }
      }
    }
  }

  private def deserialiseRequest(req: Request): Task[Either[String, RenderRequest]] = {
    req
      .as(jsonOf[RenderRequest])
      .attempt
      .map { (requestAttempt: Attempt[RenderRequest]) =>
        requestAttempt.left.map { a =>
          s"Failed to deserialise resonse body: ${a.getMessage}"
        }
      }
  }

  private def buildApiResponse(renderResult: FailedOr[RenderedPrintPdf]): Task[Response] = {
    def handleError(error: Interpreters.Error): Task[Response] = {
      error.errorCode match {
        case TemplateDownloadFailed => NotFound(error.reason)
        case MissingTemplateData => UnprocessableEntity(error.reason)
        case _ => InternalServerError(error.reason)

      }
    }

    renderResult match {
      case Left(l: Interpreters.Error) => handleError(l)
      case Right(r) => Ok(RenderResponse(r).asJson)
    }
  }
}
