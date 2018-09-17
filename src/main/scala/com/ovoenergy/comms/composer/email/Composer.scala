package com.ovoenergy.comms.composer
package email

import cats.Id
import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

trait Composer[F[_]]{
  def retrieveTemplate(incomingEvent: OrchestratedEmailV4): F[EmailTemplate[Id]]

  def render(incomingEvent: OrchestratedEmailV4, template: EmailTemplate[Id]): F[RenderedEmail]

  def lookupSender(template: EmailTemplate[Id]): F[EmailSender]

  def hashValue[A](a: A): F[String]
}