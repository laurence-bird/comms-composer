package com.ovoenergy.comms.composer.print

import cats.Id
import cats.free.Free
import cats.free.Free.liftF
import com.ovoenergy.comms.model.MetadataV2
import com.ovoenergy.comms.model.print.{ComposedPrint, OrchestratedPrint}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

object PrintComposer {

  type PrintComposer[A] = Free[PrintComposerA, A]

  def retrieveTemplate(incomingEvent: OrchestratedPrint): PrintComposer[PrintTemplate[Id]] = {
    liftF(RetrieveTemplate(incomingEvent))
  }

  def render(incomingEvent: OrchestratedPrint, template: PrintTemplate[Id]): PrintComposer[RenderedPrintHtml] = {
    liftF(Render(incomingEvent, template))
  }

  def buildEvent(incomingEvent: OrchestratedPrint, renderedPrint: RenderedPrintHtml): ComposedPrint =
    ComposedPrint(
      metadata = MetadataV2.fromSourceMetadata("comms-composer", incomingEvent.metadata),
      internalMetadata = incomingEvent.internalMetadata,
      pdfIdentifier = renderedPrint.htmlBody,
      expireAt = incomingEvent.expireAt
    )

  def program(event: OrchestratedPrint): Free[PrintComposerA, ComposedPrint] = {
    for {
      template <- retrieveTemplate(event)
      rendered <- render(event, template)
    } yield buildEvent(event, rendered)
  }

}
