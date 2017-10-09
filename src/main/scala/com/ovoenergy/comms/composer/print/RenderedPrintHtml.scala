package com.ovoenergy.comms.composer.print

case class RenderedPrintHtml(htmlBody: String)

case class RenderedPrintPdf(pdfBody: Array[Byte])
