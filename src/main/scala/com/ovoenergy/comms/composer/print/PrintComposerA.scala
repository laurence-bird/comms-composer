package com.ovoenergy.comms.composer.print

import cats.Id
import com.ovoenergy.comms.composer.rendering.templating.{BuildHandlebarsData, CommTemplateData, PrintTemplateData}
import com.ovoenergy.comms.model.{CommManifest, TemplateManifest}
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate

sealed trait PrintComposerA[T]

case class RetrieveTemplate(templateManifest: TemplateManifest) extends PrintComposerA[PrintTemplate[Id]]

case class RenderPrintHtml(handlebarsData: CommTemplateData,
                           template: PrintTemplate[Id],
                           templateManifest: TemplateManifest)
    extends PrintComposerA[RenderedPrintHtml]

case class RenderPrintPdf(renderedPrintHtml: RenderedPrintHtml, templateManifest: TemplateManifest)
    extends PrintComposerA[RenderedPrintPdf]

case class PersistRenderedPdf(incomingEvent: OrchestratedPrintV2, renderedPrintPdf: RenderedPrintPdf)
    extends PrintComposerA[String]
