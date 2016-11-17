package com.ovoenergy.comms

import java.io.IOException

import com.github.jknack.handlebars.{Handlebars, Helper, Options}
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.{AbstractTemplateLoader, StringTemplateSource, TemplateLoader, TemplateSource}

import scala.collection.JavaConverters._
import scala.collection.mutable
import java.util.{Map => JMap}

import cats.Apply
import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.instances.set._
import cats.instances.option._
import cats.syntax.traverse._

import shapeless.LabelledGeneric

object Rendering {

  private sealed trait FragmentType
  private object FragmentType {
    case object Html extends FragmentType
    case object Text extends FragmentType
  }

  private val templateCache = new ConcurrentMapTemplateCache()

  type MissingKeysOr[A] = Validated[Set[String], A]

  def render(commManifest: CommManifest,
             template: Template,
             data: Map[String, String],
             customerProfile: CustomerProfile): Either[String, RenderedEmail] = {

    val context: JMap[String, AnyRef] = (data +
      ("profile" -> profileToMap(customerProfile))).asJava
    // TODO add system data to context

    val subject: MissingKeysOr[String] = {
      val handlebars = new HandlebarsWrapper(customTemplateLoader = None)
      val filename = buildFilename(commManifest, "subject")
      handlebars.render(filename, template.subject)(context)
    }
    val htmlBody: MissingKeysOr[String] = {
      val templateLoader = new FragmentTemplateLoader(commManifest, template.htmlFragments, FragmentType.Html)
      val handlebars = new HandlebarsWrapper(Some(templateLoader))
      val filename = buildFilename(commManifest, "htmlBody")
      handlebars.render(filename, template.htmlBody)(context)
    }
    val textBody: Option[MissingKeysOr[String]] =
      template.textBody map { tb =>
        val templateLoader = new FragmentTemplateLoader(commManifest, template.textFragments, FragmentType.Text)
        val handlebars = new HandlebarsWrapper(Some(templateLoader))
        val filename = buildFilename(commManifest, "textBody")
        handlebars.render(filename, tb)(context)
      }

    val missingKeysOrResult: MissingKeysOr[RenderedEmail] =
      Apply[MissingKeysOr].map3(subject, htmlBody, textBody.sequenceU) {
        case (s, h, t) => RenderedEmail(s, h, t)
      }

    missingKeysOrResult
      .leftMap(missingKeys => s"The template referenced the following missing keys: ${missingKeys.mkString(", ")}")
      .toEither
  }

  /*
   Builds a "filename" for a Mustache template.
   This is not actually a filename. It's actually a key for use by the template cache.
   */
  private def buildFilename(commManifest: CommManifest, suffixes: String*): String =
    (Seq(commManifest.commType, commManifest.name, commManifest.version) ++ suffixes).mkString("::")

  private def profileToMap(profile: CustomerProfile): JMap[String, String] = {
    import shapeless.ops.record._
    val generic = LabelledGeneric[CustomerProfile]
    val fieldsHlist = Fields[generic.Repr].apply(generic.to(profile))
    val fieldsList = fieldsHlist.toList[(Symbol, String)]
    fieldsList.map {
      case (sym, value) => (sym.name, value)
    }.toMap.asJava
  }

  private class FragmentTemplateLoader(commManifest: CommManifest,
                                       fragments: Map[String, Mustache],
                                       fragmentType: FragmentType)
      extends AbstractTemplateLoader {
    override def sourceAt(partialName: String): TemplateSource = {
      fragments.get(partialName) match {
        case Some(mustache) =>
          val filename = buildFilename(commManifest, "fragments", fragmentType.toString, partialName)
          new StringTemplateSource(filename, mustache.content)
        case None =>
          throw new IOException(s"Template references a non-existent $fragmentType fragment: $partialName")
      }
    }
  }

  private class HandlebarsWrapper(customTemplateLoader: Option[TemplateLoader]) {
    private val missingKeys = mutable.Set.empty[String]

    private val helperRegistry = {
      val reg = new DefaultHelperRegistry()
      reg.registerHelperMissing(new Helper[JMap[String, AnyRef]] {
        override def apply(context: JMap[String, AnyRef], options: Options): AnyRef = {
          missingKeys.add(options.helperName)
          ""
        }
      })
      reg
    }

    private val handlebars = {
      val base = customTemplateLoader match {
        case Some(templateLoader) => new Handlebars(templateLoader)
        case None => new Handlebars()
      }
      base.`with`(templateCache).`with`(helperRegistry)
    }

    def render(filename: String, template: Mustache)(context: JMap[String, AnyRef]): MissingKeysOr[String] = {
      val compiledTemplate = handlebars.compile(new StringTemplateSource(filename, template.content))
      val result = compiledTemplate.apply(context)
      if (missingKeys.isEmpty)
        Valid(result)
      else
        Invalid(missingKeys.toSet)
    }

  }

}
