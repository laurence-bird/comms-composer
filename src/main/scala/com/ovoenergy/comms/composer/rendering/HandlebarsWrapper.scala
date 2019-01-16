package com.ovoenergy.comms.composer
package rendering

import java.util.{Map => JMap}

import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.StringTemplateSource
import com.github.jknack.handlebars.{Helper, Handlebars, Options, Context}
import com.ovoenergy.comms.model.{MissingTemplateData, InvalidTemplate}

import scala.collection.mutable
import scala.util.{Success, Failure, Try}

import java.util.{Map => JMap, HashMap => JHashMap, List => JList, LinkedList => JLinkedList}

trait HandlebarsWrapper {

  def compile(
      fileName: String,
      templateRawContent: String,
      context: Map[String, AnyRef]): Either[Errors, String]
}

object HandlebarsWrapper {

  def javize(x: AnyRef): AnyRef = x match {
    case map: Map[String, AnyRef] =>
      // TODO map + asJava ?
      val jmap = new JHashMap[String, AnyRef](map.size)
      map.foreach { case (k, v) => jmap.put(k, javize(v)) }
      jmap
    case seq: Seq[AnyRef] =>
      // TODO map + asJava ?
      val jlist: JList[AnyRef] = new JLinkedList[AnyRef]()
      seq.foreach { x =>
        jlist.add(javize(x))
      }
      jlist
    case str: String => str
  }

  // TODO: Lift to F
  def apply: HandlebarsWrapper = new HandlebarsWrapper {
    override def compile(
        fileName: String,
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
        // TODO we should cache the compilation
        val compiledTemplate =
          handlebars.compile(new StringTemplateSource(fileName, templateRawContent))

        // TODO rather than create a java map, we pass a ValueResolver to the
        // handlebar that is able to use the TemplateData
        compiledTemplate.apply(javize(context))
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
