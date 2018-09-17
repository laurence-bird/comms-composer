package com.ovoenergy.comms.composer.print

import io.circe.{Decoder, Encoder}
import java.util.Base64

import com.ovoenergy.comms.composer.v2.model.{Fragment, PrintBody}
import io.circe.Decoder._

import scala.util.Try

case class RenderedPrintHtml(htmlBody: String)

case class RenderedPrintPdf(fragment: Fragment)
object RenderedPrintPdf {
  implicit def renderedPrintPdfCirceEncoder: Encoder[RenderedPrintPdf] =
    Encoder.encodeString.contramap[RenderedPrintPdf] {
      case PrintBody(body) => Base64.getEncoder.encodeToString(body) // TODO: Sort me out
    }

  implicit def renderedPrintPdfCirceDecoder: Decoder[RenderedPrintPdf] =
    decodeString
      .emapTry(base64 => Try(Base64.getDecoder.decode(base64)))
      .map(x => RenderedPrintPdf(PrintBody(x)))

}
