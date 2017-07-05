package com.ovoenergy.comms.composer.email

import cats.Id
import com.ovoenergy.comms.model.CommType._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

object SenderLogic {

  def chooseSender(template: EmailTemplate[Id], commType: CommType): EmailSender =
    template.sender.getOrElse(defaultSender(commType))

  private def defaultSender(commType: CommType): EmailSender = commType match {
    // TODO check these values with somebody
    case Service => EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
    case Regulatory => EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
    case Marketing => EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
  }
}
