package com.ovoenergy.comms.composer.rendering

import java.time.{Clock, ZonedDateTime}
import java.util
import java.util.{Map => JMap}

import com.ovoenergy.comms.model._
import shapeless.{HList, Inl, Inr, LabelledGeneric}

import scala.collection.JavaConverters._
import shapeless.ops.hlist.ToTraversable

trait Rendering {

  def buildHandlebarsContext(templateData: Map[String, TemplateData],
                             customerData: JMap[String, AnyRef],
                             clock: Clock): JMap[String, AnyRef] = {

    def extractValueFromTemplateData(templateData: TemplateData): AnyRef = {
      templateData.value match {
        case (Inl(stringValue)) => stringValue
        case (Inr(Inl(sequence))) => sequence.map(extractValueFromTemplateData).asJava
        case (Inr(Inr(Inl(map)))) =>
          map.map({ case (key, value) => key -> extractValueFromTemplateData(value) }).asJava
        case (Inr(Inr(Inr(_)))) => throw new Exception("Unable to extract value from template data")
      }
    }

    val dataAsStrings: Map[String, AnyRef] = templateData map {
      case (key, templateData) => key -> extractValueFromTemplateData(templateData)
    }

    val a = combineJMaps(dataAsStrings.asJava, customerData)
    val b = systemVariables(clock)
    println("System variables: " + b)
    val result = combineJMaps(a, b)

    println("Result: ****" + result)
    result
  }


  def combineJMaps(jMap: JMap[String, AnyRef], jMap2: JMap[String, AnyRef]) = {
    val result = new util.HashMap[String, AnyRef]()
    result.putAll(jMap)
    result.putAll(jMap2)
    result
  }

  private def systemVariables(clock: Clock): JMap[String, AnyRef] = {
    val now = ZonedDateTime.now(clock)
    val result: Map[String, AnyRef] = Map("system" ->
      Map(
        "year" -> now.getYear.toString,
        "month" -> now.getMonth.getValue.toString,
        "dayOfMonth" -> now.getDayOfMonth.toString
      ).asJava
    )
    result.asJava
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
