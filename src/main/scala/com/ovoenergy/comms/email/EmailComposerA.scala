package com.ovoenergy.comms.email

import cats.Id
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

sealed trait EmailComposerA[T]

case class RetrieveTemplate(incomingEvent: OrchestratedEmailV2) extends EmailComposerA[EmailTemplate[Id]]

case class Render(incomingEvent: OrchestratedEmailV2, template: EmailTemplate[Id])
    extends EmailComposerA[RenderedEmail]

case class LookupSender(template: EmailTemplate[Id], commType: CommType) extends EmailComposerA[EmailSender]
