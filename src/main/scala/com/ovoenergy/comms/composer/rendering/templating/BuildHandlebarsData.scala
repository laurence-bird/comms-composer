package com.ovoenergy.comms.composer.rendering.templating

import com.ovoenergy.comms.model.TemplateData

case class HandlebarsData(templateData: Map[String, TemplateData], otherData: Map[String, Map[String, String]])

trait BuildHandlebarsData[A] {
  def apply(a: A): HandlebarsData
}
