package com.ovoenergy.comms.composer
package rendering
package templating

import com.ovoenergy.comms.model.TemplateData

case class HandlebarsData(
    templateData: Map[String, TemplateData],
    otherData: Map[String, Map[String, String]]
)
