package com.ovoenergy.comms.email

case class RenderedEmail(subject: String, htmlBody: String, textBody: Option[String])
