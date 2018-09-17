package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.composer.print.{RenderedPrintHtml, RenderedPrintPdf}
import com.ovoenergy.comms.composer.rendering.templating.CommTemplateData
import com.ovoenergy.comms.composer.sms.RenderedSMS
import com.ovoenergy.comms.model.TemplateManifest

trait Rendering[F[_]]{
  def renderEmail(time: ZonedDateTime, manifest: TemplateManifest, template: Templates.Email, emailTemplateData: CommTemplateData): F[RenderedEmail]
  def renderSms(time: ZonedDateTime, manifest: TemplateManifest, template: Templates.Sms, smsTemplateData: CommTemplateData): F[RenderedSMS]
  def renderPrintHtml(time: ZonedDateTime, manifest: TemplateManifest, template: Templates.Print, printTemplateData: CommTemplateData): F[RenderedPrintHtml]
  def renderPrintPdf(html: RenderedPrintHtml, manifest: TemplateManifest): F[RenderedPrintPdf]
}
