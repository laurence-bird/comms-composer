package com.ovoenergy.comms.sms

import cats.Id
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

sealed trait SMSComposerA[T]

case class RetrieveTemplate(incomingEvent: OrchestratedSMS) extends SMSComposerA[SMSTemplate[Id]]

case class Render(incomingEvent: OrchestratedSMS, template: SMSTemplate[Id]) extends SMSComposerA[RenderedSMS]
