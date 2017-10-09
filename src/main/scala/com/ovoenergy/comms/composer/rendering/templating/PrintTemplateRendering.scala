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
import com.ovoenergy.comms.model.{CommManifest, CustomerAddress, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

object PrintTemplateRendering extends Rendering {

  def renderHtml(orchestratedPrint: OrchestratedPrint,
                 template: PrintTemplate[Id],
                 clock: Clock): Either[FailedToRender, RenderedPrintHtml] = {

    val customerProfileMap: Map[String, Map[String, String]] = orchestratedPrint.customerProfile
      .map(profile => Map("profile" -> valueToMap(profile)))
      .getOrElse(Map.empty)

    val customerAddressMap: Map[String, Map[String, String]] = Map("address" -> valueToMap(orchestratedPrint.address))

    val customerData: Map[String, Map[String, String]] =
      Monoid.combine(customerProfileMap, customerAddressMap)

    val context = buildHandlebarsContext(
      orchestratedPrint.templateData,
      customerData,
      clock
    )

    val htmlBody: ErrorsOr[String] = {
      val filename = buildFilename(orchestratedPrint.metadata.commManifest, model.Print, "htmlBody")
      HandlebarsWrapper.render(filename, template.body)(context)
    }

    htmlBody
      .map(renderedHtmlBody => RenderedPrintHtml(renderedHtmlBody))
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }
}
