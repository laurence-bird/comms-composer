package com.ovoenergy.comms.composer
package v2

import com.ovoenergy.comms.templates.model.EmailSender

object model {

  sealed trait Fragment
  object Fragment {
    case class EmailSubject(content: String) extends Fragment
    case class EmailHtmBody(content: String) extends Fragment
    case class EmailTextBody(content: String) extends Fragment
    case class EmailSender(content: String) extends Fragment
    case class SmsSender(content: String) extends Fragment
    case class SmsBody(content: String) extends Fragment
    case class PrintBody(content: Array[Byte]) extends Fragment
  }

  object Email {
    def chooseSender(template: Templates.Email): EmailSender =
      template.sender.getOrElse(defaultSender)

    val defaultSender = EmailSender("Ovo Energy", "no-reply@ovoenergy.com")

    case class Rendered(subject: Fragment, htmlBody: Fragment, textBody: Option[Fragment])
  }

}
