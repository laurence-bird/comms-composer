package com.ovoenergy.comms.composer.rendering.pdf

import cats.~>
import com.ovoenergy.comms.composer.Interpreters.FailedOr
import com.ovoenergy.comms.composer.print.{PrintComposer, PrintComposerA, RenderedPrintPdf}
import com.ovoenergy.comms.model.{CommManifest, TemplateData}
import fs2.{Strategy, Task}
import cats.instances.either._
import cats.syntax.all._

object PrintRendering {
  def renderPrint(interpreter: ~>[PrintComposerA, FailedOr])(commManifest: CommManifest, data: Map[String, TemplateData])(implicit strategy: Strategy): Task[FailedOr[RenderedPrintPdf]] = {
    Task
      .apply(PrintComposer.httpProgram(commManifest, data).foldMap(interpreter))
  }
}
