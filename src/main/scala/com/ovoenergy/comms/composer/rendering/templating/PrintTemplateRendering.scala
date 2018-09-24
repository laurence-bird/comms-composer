package com.ovoenergy.comms.composer.rendering.templating

import java.time.Clock

import cats.Id
import cats.implicits._
import cats.kernel.Monoid
import com.ovoenergy.comms.composer.print.RenderedPrintHtml
import com.ovoenergy.comms.composer.rendering.pdf.DocRaptorClient
import com.ovoenergy.comms.composer.rendering.{ErrorsOr, FailedToRender}
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

import scala.collection.mutable

object PrintTemplateRendering extends Rendering {

  def renderHtml(
      handlebarsData: HandlebarsData,
      templateManifest: TemplateManifest,
      template: PrintTemplate[Id],
      clock: Clock): Either[FailedToRender, RenderedPrintHtml] = {

    val context = buildHandlebarsContext(
      handlebarsData,
      clock
    )

    val htmlBody: ErrorsOr[String] = {
      val filename = buildFilename(templateManifest, model.Print, "htmlBody")
      HandlebarsWrapper.render(filename, template.body)(context)
    }

    htmlBody
      .map(renderedHtmlBody => RenderedPrintHtml(renderedHtmlBody))
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }
}
