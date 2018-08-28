package com.ovoenergy.comms.composer.email

import cats.Id
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

sealed trait EmailComposerA[T]

case class RetrieveTemplate(incomingEvent: OrchestratedEmailV4) extends EmailComposerA[EmailTemplate[Id]]

case class Render(incomingEvent: OrchestratedEmailV4, template: EmailTemplate[Id])
    extends EmailComposerA[RenderedEmail]

case class LookupSender(template: EmailTemplate[Id]) extends EmailComposerA[EmailSender]

case class HashString(str: String) extends EmailComposerA[String]
