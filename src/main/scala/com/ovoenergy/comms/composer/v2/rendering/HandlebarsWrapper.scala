package com.ovoenergy.comms.composer
package v2
package rendering

import java.util.{Map => JMap}

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.StringTemplateSource
import com.github.jknack.handlebars.{Handlebars, Helper, Options}
import com.ovoenergy.comms.composer.rendering.Errors
import com.ovoenergy.comms.model.{InvalidTemplate, MissingTemplateData}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

trait HandlebarsWrapper {

  def compile(fileName: String, templateRawContent: String, context: Map[String, AnyRef]): Either[Errors, String]
}

object HandlebarsWrapper {

  // TODO: Lift to F
  def apply: HandlebarsWrapper = new HandlebarsWrapper {
    override def compile(fileName: String,
                         templateRawContent: String,
                         context: Map[String, AnyRef]): Either[Errors, String] = {
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
        val compiledTemplate = handlebars.compile(new StringTemplateSource(fileName, templateRawContent))
        compiledTemplate.apply(context)
      } match { // note: Try has a `fold` function in Scala 2.12 :)
        case Success(result) =>
          if (missingKeys.isEmpty)
            Right(result)
          else
            Left(Errors(missingKeys = missingKeys.toSet, exceptions = Nil, MissingTemplateData))
        case Failure(e) =>
          Left(Errors(missingKeys = Set.empty, exceptions = List(e), InvalidTemplate))
      }
    }
  }
}
