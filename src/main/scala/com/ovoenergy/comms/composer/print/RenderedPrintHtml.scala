package com.ovoenergy.comms.composer.print

import io.circe.{Decoder, Encoder}
import java.util.Base64
import io.circe.Decoder._

import scala.util.Try

case class RenderedPrintHtml(htmlBody: String)

case class RenderedPrintPdf(pdfBody: Array[Byte])
object RenderedPrintPdf {
  implicit def renderedPrintPdfCirceEncoder: Encoder[RenderedPrintPdf] =
    Encoder.encodeString.contramap[RenderedPrintPdf](x => Base64.getEncoder.encodeToString(x.pdfBody))

  implicit def renderedPrintPdfCirceDecoder: Decoder[RenderedPrintPdf] =
    decodeString
      .emapTry(base64 => Try(Base64.getDecoder.decode(base64)))
      .map(RenderedPrintPdf.apply)

}
