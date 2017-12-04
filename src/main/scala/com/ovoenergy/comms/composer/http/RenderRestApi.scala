package com.ovoenergy.comms.composer.http

import java.time.LocalDate
import java.util.Base64

import com.ovoenergy.comms.composer.http.RenderRestApi._
import com.ovoenergy.comms.composer.print.RenderedPrintPdf
import com.ovoenergy.comms.model.TemplateData
import fs2.Task
import org.http4s.HttpService
import org.http4s.dsl._
import org.http4s.circe._
import io.circe.literal._
import org.http4s.multipart.Multipart
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.implicits._

case class CommName(value: String) extends AnyVal
case class CommVersion(value: String) extends AnyVal

object RenderRestApi {

  case class RenderRequest(data: TemplateData)
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

  def renderService: HttpService = HttpService {
    case req @ POST -> Root / "render" / CommNamePath(commName) / CommVersionPath(commVersion) / "print" =>
      for {
        renderRequest <- req.as(jsonOf[RenderRequest])
        renderedPrint <- renderPrint(commName, commVersion, renderRequest.data)
        response <- Ok(RenderResponse(renderedPrint).asJson)
      } yield response
  }

  def renderPrint(commName: CommName, commVersion: CommVersion, data: TemplateData): Task[RenderedPrintPdf] = {
    ???
  }
}
