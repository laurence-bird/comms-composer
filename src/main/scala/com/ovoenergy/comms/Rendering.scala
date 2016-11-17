package com.ovoenergy.comms

import java.io.IOException

import com.github.jknack.handlebars.{Handlebars, Helper, Options}
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.{AbstractTemplateLoader, StringTemplateSource, TemplateSource}

import scala.collection.JavaConverters._
import java.util.{Map => JMap}

import shapeless.LabelledGeneric

object Rendering {

  private sealed trait FragmentType
  private object FragmentType {
    case object Html extends FragmentType
    case object Text extends FragmentType
  }

  private val templateCache = new ConcurrentMapTemplateCache()

  private val helperRegistry = {
    // TODO collect invalid placeholders for reporting
    val reg = new DefaultHelperRegistry()
    reg.registerHelperMissing(new Helper[JMap[String, Any]] {
      override def apply(context: JMap[String, Any], options: Options): AnyRef = {
        println(s"context: $context")
        println(s"options: ${options.helperName}")
        ""
      }
    })
    reg
  }

  def render(commManifest: CommManifest,
             template: Template,
             data: Map[String, String],
             customerProfile: CustomerProfile): RenderedEmail = {

    val context = data +
        ("profile" -> profileToMap(customerProfile))
    // TODO add system data to context

    // TODO refactor
    val subject = {
      val handlebars = new Handlebars().`with`(templateCache)
      // not a real filename, actually a cache key
      val filename = buildFilename(commManifest, "subject")
      val mustacheTemplate = handlebars.compile(new StringTemplateSource(filename, template.subject.content))
      mustacheTemplate.apply(context.asJava)
    }
    val htmlBody = {
      val handlebars =
        new Handlebars(new FragmentTemplateLoader(commManifest, template.htmlFragments, FragmentType.Html))
          .`with`(templateCache)
      val filename = buildFilename(commManifest, "htmlBody")
      val mustacheTemplate = handlebars.compile(new StringTemplateSource(filename, template.htmlBody.content))
      mustacheTemplate.apply(context.asJava)
    }
    val textBody = template.textBody map { tb =>
      val handlebars =
        new Handlebars(new FragmentTemplateLoader(commManifest, template.textFragments, FragmentType.Text))
          .`with`(templateCache)
      val filename = buildFilename(commManifest, "textBody")
      val mustacheTemplate = handlebars.compile(new StringTemplateSource(filename, tb.content))
      mustacheTemplate.apply(context.asJava)
    }

    RenderedEmail(subject, htmlBody, textBody)
  }

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

}
