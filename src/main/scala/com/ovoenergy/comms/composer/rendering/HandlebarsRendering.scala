package com.ovoenergy.comms.composer
package rendering

import java.time.ZonedDateTime

import com.ovoenergy.comms.composer.rendering.templating.{CommTemplateData, HandlebarsData}
import com.ovoenergy.comms.model.TemplateData
import com.ovoenergy.comms.templates.model.HandlebarsTemplate
import shapeless.{Inl, Inr}

trait HandlebarsRendering {
  def render(
      template: HandlebarsTemplate,
      time: ZonedDateTime,
      templateData: CommTemplateData,
      fileName: String): Either[Errors, String]
}

object HandlebarsRendering {
  def apply(handlebars: HandlebarsWrapper): HandlebarsRendering = new HandlebarsRendering {
    override def render(
        template: HandlebarsTemplate,
        time: ZonedDateTime,
        commTemplateData: CommTemplateData,
        fileName: String): Either[Errors, String] = {

      val context = buildHandlebarsContext(commTemplateData.buildHandlebarsData, time)

      handlebars.compile(fileName, template.rawExpandedContent, context)
    }
  }

  def buildHandlebarsContext(
      handlebarsData: HandlebarsData,
      time: ZonedDateTime): Map[String, AnyRef] = {

    def extractValueFromTemplateData(templateData: TemplateData): AnyRef = {
      // TODO use select
      templateData.value match {
        case Inl(stringValue) =>
          stringValue
        case Inr(Inl(sequence)) =>
          sequence.map(extractValueFromTemplateData)
        case Inr(Inr(Inl(map))) =>
          map.map {
            case (key, value) =>
              key -> extractValueFromTemplateData(value)
          }
        case _ =>
          throw new Exception("Unable to extract value from template data")
      }
    }

    val data: Map[String, AnyRef] = handlebarsData.templateData.map {
      case (key, value) => key -> extractValueFromTemplateData(value)
    }

    // TODO: It should do a deep merge
    // TODO: I believe the handlebarsData should contain the data already merged
    systemVariables(time) ++ handlebarsData.otherData ++ data
  }

  private def systemVariables(time: ZonedDateTime): Map[String, Map[String, String]] = {
    Map(
      "system" -> Map(
        "year" -> time.getYear.toString,
        "month" -> time.getMonth.getValue.toString,
        "dayOfMonth" -> time.getDayOfMonth.toString,
      )
    )

  }
}
