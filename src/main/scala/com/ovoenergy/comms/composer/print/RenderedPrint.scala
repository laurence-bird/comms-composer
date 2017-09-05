package com.ovoenergy.comms.composer.print

case class RenderedPrint(htmlFooter: Option[String], htmlBody: String, htmlHeader: Option[String])
