package com.ovoenergy.comms.composer.v2

import com.ovoenergy.comms.composer.v2.model.Print

trait PdfRendering[F[_]] {
  def apply[F[_]](renderedPrintHtml: Print.HtmlBody): F[Print.RenderedPdf]
}
