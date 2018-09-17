package com.ovoenergy.comms.composer
package rendering

import cats.Id

import com.ovoenergy.comms.model.TemplateManifest

import com.ovoenergy.comms.templates.model.template.processed._
import email.EmailTemplate
import print.PrintTemplate
import sms.SMSTemplate

trait Templates[F[_]] {

  def getEmailTemplate(templateManifest: TemplateManifest): F[EmailTemplate[Id]]

  def getSMSTemplate(templateManifest: TemplateManifest): F[SMSTemplate[Id]]

  def getPrintTemplate(templateManifest: TemplateManifest): F[PrintTemplate[Id]]

}
