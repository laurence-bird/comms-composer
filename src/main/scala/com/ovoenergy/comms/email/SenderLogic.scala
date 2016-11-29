package com.ovoenergy.comms.email

import com.ovoenergy.comms.model.CommType._
import com.ovoenergy.comms.model._

object SenderLogic {

  def chooseSender(template: EmailTemplate, commType: CommType): EmailSender = {
    template.sender getOrElse defaultSender(commType)
  }

  private def defaultSender(commType: CommType): EmailSender = commType match {
    // TODO check these values with somebody
    case Service => EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
    case Regulatory => EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
    case Marketing => EmailSender("Ovo Energy", "no-reply@ovoenergy.com")
  }

}
