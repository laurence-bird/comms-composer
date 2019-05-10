package com.ovoenergy.comms.composer
package rendering

import shapeless._

import java.time.ZonedDateTime
import java.util.{Map => JMap}
import scala.collection.JavaConverters._

import com.github.jknack.handlebars._
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.ValueResolver

import com.ovoenergy.comms.model.TemplateData

object MissingKeys {

  val empty: MissingKeys = MissingKeys(List.empty)

  private val ContextKey = "_missingKeys"

  private def extractMissingKeys(ctx: Context) =
    Option(ctx.data[AnyRef](ContextKey))
      .collect {
        case mk: MissingKeys => mk
      }
      .getOrElse(MissingKeys.empty)

  private class MissingKeyHelper[A] extends Helper[A] {
    def apply(context: A, options: Options): AnyRef = {
      val missingKeys =
        options.data(
          ContextKey,
          extractMissingKeys(options.context).withKey(options.helperName)
        )
      // Return a en empty string
      ""
    }
  }

  def helper[A]: Helper[A] = new MissingKeyHelper[A]

  def missingKeysFor(ctx: Context): List[String] =
    extractMissingKeys(ctx).values
}

case class MissingKeys(values: List[String]) {
  def withKey(key: String) = copy(values = key :: values)
}

class TemplateDataValueResolver extends ValueResolver {

  def resolveValue(td: TemplateData.TD): AnyRef = td match {
    case Inl(x) => x
    case Inr(Inl(xs)) => xs.asJava
    case Inr(Inr(Inl(xs))) => xs
    case _ => ValueResolver.UNRESOLVED
  }

  def resolveValue(templateData: TemplateData): AnyRef =
    resolveValue(templateData.value)

  def resolveProperties(templateData: TemplateData): Map[String, AnyRef] =
    resolveProperties(templateData.value)

  def resolveProperties(td: TemplateData.TD): Map[String, AnyRef] = td match {
    case Inr(Inr(Inl(xs))) => xs
    case _ => Map.empty
  }

  def resolve(context: AnyRef, name: String): AnyRef = {
    val result = context match {
      case TemplateData(Inr(Inr(Inl(xs)))) => xs.get(name).fold(ValueResolver.UNRESOLVED)(resolve)
      case _ => ValueResolver.UNRESOLVED
    }

    result;
  }

  def resolve(context: AnyRef): AnyRef = context match {
    case TemplateData(Inl(x)) => x
    case TemplateData(Inr(Inl(xs))) => xs.asJava
    case templateData @ TemplateData(Inr(Inr(Inl(_)))) => templateData
    case _ => ValueResolver.UNRESOLVED
  }

  def propertySet(context: AnyRef): java.util.Set[java.util.Map.Entry[String, AnyRef]] = {
    val properties: Map[String, AnyRef] = context match {
      case TemplateData(td) =>
        resolveProperties(td)
      case _ =>
        Map.empty
    }
    properties.asJava.entrySet
  }

}
