package com.ovoenergy.comms.composer
package v2
package rendering

import java.time.ZonedDateTime

import cats.data.Validated
import com.ovoenergy.comms.composer.rendering.Errors
import com.ovoenergy.comms.composer.rendering.templating.{CommTemplateData, HandlebarsData}
import com.ovoenergy.comms.model.TemplateData
import com.ovoenergy.comms.templates.model.HandlebarsTemplate
import shapeless.{Inl, Inr}

trait HandlebarsRendering {
  def render(template: HandlebarsTemplate,
             time: ZonedDateTime,
             templateData: CommTemplateData,
             fileName: String): Either[Errors, String]
}

object HandlebarsRendering {
  def apply(handlebars: HandlebarsWrapper): HandlebarsRendering = new HandlebarsRendering {
    override def render(template: HandlebarsTemplate,
                        time: ZonedDateTime,
                        commTemplateData: CommTemplateData,
                        fileName: String): Either[Errors, String] = {

      val context = buildHandlebarsContext(commTemplateData.buildHandlebarsData, time)

      handlebars.compile(fileName, template.rawExpandedContent, context)
    }
  }

  def buildHandlebarsContext(handlebarsData: HandlebarsData, time: ZonedDateTime): Map[String, AnyRef] = {

    def extractValueFromTemplateData(templateData: TemplateData): AnyRef = {
      templateData.value match {
        case (Inl(stringValue)) => stringValue
        case (Inr(Inl(sequence))) => sequence.map(extractValueFromTemplateData)
        case (Inr(Inr(Inl(map)))) =>
          map.map({ case (key, value) => key -> extractValueFromTemplateData(value) })
        case (Inr(Inr(Inr(_)))) => throw new Exception("Unable to extract value from template data") // TODO: Naughty
      }
    }

    val dataAsStrings: Map[String, AnyRef] = handlebarsData.templateData map {
      case (key, templateData) => key -> extractValueFromTemplateData(templateData)
    }

    systemVariables(time) ++ handlebarsData.otherData ++ dataAsStrings
  }

  private def systemVariables(time: ZonedDateTime): Map[String, Map[String, String]] = {
    Map(
      "system" ->
        Map(
          "year" -> time.getYear.toString,
          "month" -> time.getMonth.getValue.toString,
          "dayOfMonth" -> time.getDayOfMonth.toString
        ))
  }
}
