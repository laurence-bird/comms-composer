package com.ovoenergy.comms.composer.rendering

import java.time.Clock
import java.util

import cats.implicits._
import cats.{Apply, Id}
import com.ovoenergy.comms.composer.print.RenderedPrint
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.{CommManifest, CustomerAddress, CustomerProfile, TemplateData}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

import scala.collection.JavaConverters._

object PrintRendering extends Rendering {

  def renderPrint(clock: Clock)(commManifest: CommManifest,
                                template: PrintTemplate[Id],
                                data: Map[String, TemplateData],
                                customerAddress: CustomerAddress,
                                customerProfile: Option[CustomerProfile]): Either[FailedToRender, RenderedPrint] = {

    val customerProfileMap: Map[String, AnyRef] = customerProfile
      .map(profile => Map("profile" -> valueToMap(profile).asJava))
      .getOrElse(Map.empty)

    val customerAddressMap: Map[String, AnyRef] = Map("address" -> valueToMap(customerAddress).asJava)

    val context = buildHandlebarsContext(
      data,
      combineJMaps(customerAddressMap.asJava,customerProfileMap.asJava),
      clock
    )

    val htmlFooter: Option[ErrorsOr[String]] = {
      template.footer.map{ footer =>
        val filename = buildFilename(commManifest, model.Post, "htmlFooter")
        HandlebarsWrapper.render(filename, footer)(context)
      }
    }

    val htmlBody: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, model.Post, "htmlBody")
      HandlebarsWrapper.render(filename, template.body)(context)
    }

    val htmlHeader: Option[ErrorsOr[String]] = {
      template.header.map{ header =>
        val filename = buildFilename(commManifest, model.Post, "htmlHeader")
        HandlebarsWrapper.render(filename, header)(context)
      }
    }

    val errorsOrResult: ErrorsOr[RenderedPrint] = {
      Apply[ErrorsOr].map3(htmlFooter.sequenceU, htmlBody, htmlHeader.sequenceU){
        case(f, b, h) => RenderedPrint(f, b, h)
      }
    }

    errorsOrResult
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }
}
