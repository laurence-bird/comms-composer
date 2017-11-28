package com.ovoenergy.comms.composer.rendering.templating

import com.ovoenergy.comms.model.TemplateData

trait CanBuildTemplateData[A] {
  def buildTemplateData(a: A): Map[String, TemplateData]
}
