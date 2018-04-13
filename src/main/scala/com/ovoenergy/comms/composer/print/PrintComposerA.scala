package com.ovoenergy.comms.composer.print

import cats.Id
import com.ovoenergy.comms.composer.rendering.templating.{BuildHandlebarsData, CommTemplateData, PrintTemplateData}
import com.ovoenergy.comms.model.CommManifest
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

sealed trait PrintComposerA[T]

case class RetrieveTemplate(commManifest: CommManifest) extends PrintComposerA[PrintTemplate[Id]]

case class RenderPrintHtml(handlebarsData: CommTemplateData, template: PrintTemplate[Id], commManifest: CommManifest)
    extends PrintComposerA[RenderedPrintHtml]

case class RenderPrintPdf(renderedPrintHtml: RenderedPrintHtml, commManifest: CommManifest)
    extends PrintComposerA[RenderedPrintPdf]

case class PersistRenderedPdf(incomingEvent: OrchestratedPrint, renderedPrintPdf: RenderedPrintPdf)
    extends PrintComposerA[String]
