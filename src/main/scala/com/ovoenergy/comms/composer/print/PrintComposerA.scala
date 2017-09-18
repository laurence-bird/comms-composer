package com.ovoenergy.comms.composer.print

import cats.Id
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

sealed trait PrintComposerA[T]

case class RetrieveTemplate(incomingEvent: OrchestratedPrint) extends PrintComposerA[PrintTemplate[Id]]

case class Render(incomingEvent: OrchestratedPrint, template: PrintTemplate[Id])
    extends PrintComposerA[RenderedPrintHtml]
