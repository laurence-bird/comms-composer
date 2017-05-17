package com.ovoenergy.comms.rendering

import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.StringTemplateSource
import com.github.jknack.handlebars.{Handlebars, Helper, Options}
import java.util.{Map => JMap}

import cats.data.Validated.{Invalid, Valid}
import com.ovoenergy.comms.model.{InvalidTemplate, MissingTemplateData}
import com.ovoenergy.comms.templates.model.HandlebarsTemplate

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/*
 * Wrapper for Handlebars that keeps track of any references to missing keys.
 */
private[rendering] object HandlebarsWrapper {

  def render(filename: String, template: HandlebarsTemplate)(context: JMap[String, AnyRef]): ErrorsOr[String] = {
    val missingKeys = mutable.Set.empty[String]

    val helperRegistry = {
      val reg = new DefaultHelperRegistry()
      reg.registerHelperMissing(new Helper[JMap[String, AnyRef]] {
        override def apply(context: JMap[String, AnyRef], options: Options): AnyRef = {
          missingKeys.add(options.helperName)
          ""
        }
      })
      reg
    }

    val handlebars = new Handlebars().`with`(helperRegistry)

    Try {
      val compiledTemplate = handlebars.compile(new StringTemplateSource(filename, template.rawExpandedContent))
      compiledTemplate.apply(context)
    } match { // note: Try has a `fold` function in Scala 2.12 :)
      case Success(result) =>
        if (missingKeys.isEmpty)
          Valid(result)
        else
          Invalid(Errors(missingKeys = missingKeys.toSet, exceptions = Nil, MissingTemplateData))
      case Failure(e) =>
        Invalid(Errors(missingKeys = Set.empty, exceptions = List(e), InvalidTemplate))
    }
  }

}
