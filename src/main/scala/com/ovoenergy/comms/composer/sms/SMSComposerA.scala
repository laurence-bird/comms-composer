package com.ovoenergy.comms.composer.sms

import cats.Id
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate

sealed trait SMSComposerA[T]

case class RetrieveTemplate(incomingEvent: OrchestratedSMSV2) extends SMSComposerA[SMSTemplate[Id]]

case class Render(incomingEvent: OrchestratedSMSV2, template: SMSTemplate[Id]) extends SMSComposerA[RenderedSMS]
