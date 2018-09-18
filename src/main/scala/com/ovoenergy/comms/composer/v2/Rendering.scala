package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

import com.ovoenergy.comms.model.TemplateManifest

import model._
import com.ovoenergy.comms.composer.email.RenderedEmail
import com.ovoenergy.comms.composer.print.{RenderedPrintHtml, RenderedPrintPdf}
import com.ovoenergy.comms.composer.rendering.templating.CommTemplateData
import com.ovoenergy.comms.composer.sms.RenderedSMS

trait Rendering[F[_]] {
  def renderEmail(time: ZonedDateTime,
                  manifest: TemplateManifest,
                  template: Templates.Email,
                  emailTemplateData: CommTemplateData): F[Email.Rendered]
  def renderSms(time: ZonedDateTime,
                manifest: TemplateManifest,
                template: Templates.Sms,
                smsTemplateData: CommTemplateData): F[SMS.Rendered]
  def renderPrintHtml(time: ZonedDateTime,
                      manifest: TemplateManifest,
                      template: Templates.Print,
                      printTemplateData: CommTemplateData): F[Print.RenderedHtml]
  def renderPrintPdf(html: Print.RenderedHtml, manifest: TemplateManifest): F[Print.RenderedPdf]
}
