package com.ovoenergy.comms.composer
package v2

import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.composer.print.{RenderedPrintHtml, RenderedPrintPdf}
import com.ovoenergy.comms.composer.rendering.templating.CommTemplateData
import com.ovoenergy.comms.model.TemplateManifest
import org.joda.time.DateTime

trait Rendering[F[_]]{
  def renderEmail(time: Time[F], manifest: TemplateManifest, template: Templates.Email, emailTemplateData: CommTemplateData): F[RenderedEmail]
  def renderPrintHtml(time: Time[F], manifest: TemplateManifest, template: Templates.Print, emailTemplateData: CommTemplateData): F[RenderedPrintHtml]
  def renderPrintPdf(html: RenderedPrintHtml, manifest: TemplateManifest): F[RenderedPrintPdf]
}
