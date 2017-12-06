package com.ovoenergy.comms.composer.rendering.templating

import java.time.{Clock, ZonedDateTime}
import java.util
import java.util.{Map => JMap}

import com.ovoenergy.comms.model._
import shapeless.ops.hlist.ToTraversable
import shapeless.{HList, Inl, Inr, LabelledGeneric}

import scala.collection.JavaConverters._

trait Rendering {

  def buildHandlebarsContext(handlebarsData: HandlebarsData, clock: Clock): JMap[String, AnyRef] = {

    def extractValueFromTemplateData(templateData: TemplateData): AnyRef = {
      templateData.value match {
        case (Inl(stringValue)) => stringValue
        case (Inr(Inl(sequence))) => sequence.map(extractValueFromTemplateData).asJava
        case (Inr(Inr(Inl(map)))) =>
          map.map({ case (key, value) => key -> extractValueFromTemplateData(value) }).asJava
        case (Inr(Inr(Inr(_)))) => throw new Exception("Unable to extract value from template data")
      }
    }

    val dataAsStrings: Map[String, AnyRef] = handlebarsData.templateData map {
      case (key, templateData) => key -> extractValueFromTemplateData(templateData)
    }

    dataAsStrings
      .combineWith(systemVariables(clock), handlebarsData.otherData)
  }

  implicit class JMapBuilders(map1: Map[String, AnyRef]) {
    def combineWith(maps: Map[String, Map[String, String]]*): JMap[String, AnyRef] = {
      val result = new util.HashMap[String, AnyRef]()
      result.putAll(map1.asJava)

      maps.foreach { (map: Map[String, Map[String, String]]) =>
        val jMap = map.mapValues(_.asJava)
        result.putAll(jMap.asJava)
      }
      result
    }
  }

  private def systemVariables(clock: Clock): Map[String, Map[String, String]] = {
    val now = ZonedDateTime.now(clock)
    Map(
      "system" ->
        Map(
          "year" -> now.getYear.toString,
          "month" -> now.getMonth.getValue.toString,
          "dayOfMonth" -> now.getDayOfMonth.toString
        ))
  }

  /*
   Builds a "filename" for a Mustache template.
   This is not actually a filename. It's actually a key for use by the template cache.

   In fact we are not using a template cache, so the filename is not even used as a cache key,
   but it's still nice to have a unique, human-readable identifier for a Mustache template.
   */
  def buildFilename(commManifest: CommManifest, channel: Channel, suffixes: String*): String =
    (Seq(commManifest.commType, commManifest.name, commManifest.version, channel.toString) ++ suffixes).mkString("::")

  /*
  Use shapeless to turn an arbitrary value into a Map[String, String]
   */

  import shapeless.ops.record._

  def valueToMap[E, L <: HList, F <: HList](instanceToConvert: E)(
      implicit gen: LabelledGeneric.Aux[E, L],
      fields: Fields.Aux[L, F],
      toTraversableAux: ToTraversable.Aux[F, List, (Symbol, String)]): Map[String, String] = {

    val fieldsHlist = fields.apply(gen.to(instanceToConvert))
    val fieldsList = toTraversableAux(fieldsHlist)

    fieldsList.map {
      case (sym, value) => (sym.name, value)
    }.toMap
  }
}
