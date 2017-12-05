package com.ovoenergy.comms.composer.print

import io.circe.Encoder
import java.util.Base64

case class RenderedPrintHtml(htmlBody: String)

case class RenderedPrintPdf(pdfBody: Array[Byte])
object RenderedPrintPdf {
  implicit def renderedPrintPdfCirceEncoder: Encoder[RenderedPrintPdf] =
    Encoder.encodeString.contramap[RenderedPrintPdf](x => Base64.getEncoder.encodeToString(x.pdfBody))
}
