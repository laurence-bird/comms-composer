package com.ovoenergy.comms

case class RenderedEmail(subject: String, htmlBody: String, textBody: Option[String])
