package com.ovoenergy.comms.composer.email

import com.ovoenergy.comms.model.TemplateData
import shapeless.{:+:, CNil}

object Hi{
  type TD = String :+: Seq[TemplateData] :+: Map[String, TemplateData] :+: CNil
}

case class TemplateData(value: TD)

sealed trait TD
case class Str(value: String) extends TD
case class Sq(value: Seq[TemplateData]) extends TD
case class Mp(value: Map[String, TemplateData]) extends TD
