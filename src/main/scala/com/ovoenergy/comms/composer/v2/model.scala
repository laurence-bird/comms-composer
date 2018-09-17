package com.ovoenergy.comms.composer
package v2
object model {

  sealed trait Fragment

  case class EmailSubject(content: String) extends Fragment
  case class EmailHtmBody(content: String) extends Fragment
  case class EmailTextBody(content: String) extends Fragment
  case class EmailSender(content: String) extends Fragment
  case class SmsSender(content: String) extends Fragment
  case class SmsBody(content: String) extends Fragment
  case class PrintBody(content: Array[Byte]) extends Fragment

}
