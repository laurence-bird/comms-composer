package com.ovoenergy.comms

case class Sender(name: String, emailAddress: String) {
  override def toString = s"$name <$emailAddress>"
}
