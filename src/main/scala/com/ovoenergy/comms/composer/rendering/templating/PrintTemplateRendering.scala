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

import scala.collection.mutable

object PrintTemplateRendering extends Rendering {

  def renderHtml(orchestratedPrint: OrchestratedPrint,
                 template: PrintTemplate[Id],
                 clock: Clock): Either[FailedToRender, RenderedPrintHtml] = {

    val customerProfileMap: Map[String, Map[String, String]] = orchestratedPrint.customerProfile
      .map(profile => Map("profile" -> valueToMap(profile)))
      .getOrElse(Map.empty)

    val address = orchestratedPrint.address

    val addressMap = Map(
      "line1" -> Some(address.line1),
      "town" -> Some(address.town),
      "postcode" -> Some(address.postcode),
      "line2" -> address.line2,
      "county" -> address.county,
      "country" -> address.country
    ) collect { case (k, Some(v)) => (k, v) }

    val customerAddressMap: Map[String, Map[String, String]] = Map("address" -> addressMap)

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
