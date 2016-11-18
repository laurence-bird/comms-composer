package com.ovoenergy.comms.email

case class EmailSender(name: String, emailAddress: String) {
  override def toString = s"$name <$emailAddress>"
}
