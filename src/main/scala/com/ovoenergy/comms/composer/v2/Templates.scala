package com.ovoenergy.comms.composer.v2

import cats.Id
import com.ovoenergy.comms.model.TemplateManifest
import com.ovoenergy.comms.templates.model.template.{processed => templates}

trait Templates[F[_], Template] {
  def get(manifest: TemplateManifest): F[Template]
}

object Templates{
  type Email = templates.email.EmailTemplate[Id]
  type Print = templates.print.PrintTemplate[Id]
  type Sms = templates.sms.SMSTemplate[Id]

  def sms[F[_]] : Templates[F, Templates.Sms] = ???
  def email[F[_]] : Templates[F, Templates.Email] = ???
  def print[F[_]] : Templates[F, Templates.Print] = ???
}