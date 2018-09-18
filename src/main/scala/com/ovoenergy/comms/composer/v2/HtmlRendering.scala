package com.ovoenergy.comms.composer
package v2

import java.time.ZonedDateTime

import cats.data.Validated
import com.ovoenergy.comms.composer.rendering.Errors
import com.ovoenergy.comms.composer.rendering.templating.{CommTemplateData, HandlebarsData}
import com.ovoenergy.comms.model.TemplateData
import com.ovoenergy.comms.templates.model.HandlebarsTemplate
import shapeless.{Inl, Inr}

import scala.collection.JavaConverters._

trait HtmlRendering {
  def render(template: HandlebarsTemplate,
             time: ZonedDateTime,
             templateData: CommTemplateData,
             fileName: String): Validated[Errors, String]
}

object HtmlRendering {
  def apply(handlebars: HandlebarsWrapped): HtmlRendering = new HtmlRendering {
    override def render(template: HandlebarsTemplate,
                        time: ZonedDateTime,
                        commTemplateData: CommTemplateData,
                        fileName: String): Validated[Errors, String] = {

      val context = buildHandlebarsContext(commTemplateData.buildHandlebarsData, time)

      handlebars.compile(fileName, template.rawExpandedContent, context)
    }
  }

  def buildHandlebarsContext(handlebarsData: HandlebarsData, time: ZonedDateTime): Map[String, AnyRef] = {

    def extractValueFromTemplateData(templateData: TemplateData): AnyRef = {
      templateData.value match {
        case (Inl(stringValue)) => stringValue
        case (Inr(Inl(sequence))) => sequence.map(extractValueFromTemplateData).asJava
        case (Inr(Inr(Inl(map)))) =>
          map.map({ case (key, value) => key -> extractValueFromTemplateData(value) }).asJava
        case (Inr(Inr(Inr(_)))) => throw new Exception("Unable to extract value from template data") // TODO: Naughty
      }
    }

    val mapA = Map("a" -> "hi")
    val mapB = Map("a" -> "yo")
    mapA ++ mapB
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
