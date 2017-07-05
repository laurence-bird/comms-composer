package com.ovoenergy.comms.composer.email

case class RenderedEmail(subject: String, htmlBody: String, textBody: Option[String])
