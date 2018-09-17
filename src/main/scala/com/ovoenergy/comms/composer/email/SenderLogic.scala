package com.ovoenergy.comms.composer
package email

import cats.Id
import com.ovoenergy.comms.model.CommType._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

object SenderLogic {

  def chooseSender(template: EmailTemplate[Id]): EmailSender =
    template.sender.getOrElse(defaultSender)

  val defaultSender = EmailSender("Ovo Energy", "no-reply@ovoenergy.com")

}
