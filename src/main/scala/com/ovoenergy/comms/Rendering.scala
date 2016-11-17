package com.ovoenergy.comms

import java.io.IOException

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache
import com.github.jknack.handlebars.io.{AbstractTemplateLoader, StringTemplateSource, TemplateSource}

import scala.collection.JavaConverters._

object Rendering {

  private sealed trait FragmentType
  private object FragmentType {
    case object Html extends FragmentType
    case object Text extends FragmentType
  }

  private val templateCache = new ConcurrentMapTemplateCache()

  def render(commManifest: CommManifest,
             template: Template,
             data: Map[String, String],
             customerProfile: CustomerProfile): RenderedEmail = {

    // TODO add user profile and system data to context

    // TODO refactor
    val subject = {
      val handlebars = new Handlebars().`with`(templateCache)
      // not a real filename, actually a cache key
      val filename = Seq(commManifest.commType, commManifest.name, commManifest.version, "subject").mkString("::")
      val mustacheTemplate = handlebars.compile(new StringTemplateSource(filename, template.subject.content))
      mustacheTemplate.apply(data.asJava)
    }
    val htmlBody = {
      val handlebars =
        new Handlebars(new FragmentTemplateLoader(commManifest.commType, template.htmlFragments, FragmentType.Html))
          .`with`(templateCache)
      val filename = Seq(commManifest.commType, commManifest.name, commManifest.version, "htmlBody").mkString("::")
      val mustacheTemplate = handlebars.compile(new StringTemplateSource(filename, template.htmlBody.content))
      mustacheTemplate.apply(data.asJava)
    }
    val textBody = template.textBody map { tb =>
      val handlebars =
        new Handlebars(new FragmentTemplateLoader(commManifest.commType, template.textFragments, FragmentType.Text))
          .`with`(templateCache)
      val filename = Seq(commManifest.commType, commManifest.name, commManifest.version, "textBody").mkString("::")
      val mustacheTemplate = handlebars.compile(new StringTemplateSource(filename, tb.content))
      mustacheTemplate.apply(data.asJava)
    }

    RenderedEmail(subject, htmlBody, textBody)
  }

  private class FragmentTemplateLoader(commType: CommType,
                                       fragments: Map[String, Mustache],
                                       fragmentType: FragmentType)
      extends AbstractTemplateLoader {
    override def sourceAt(location: String): TemplateSource = {
      fragments.get(location) match {
        case Some(mustache) =>
          val filename = Seq(commType, "fragments", fragmentType, location).mkString("::")
          new StringTemplateSource(filename, mustache.content)
        case None =>
          throw new IOException(s"Template references a non-existent $fragmentType fragment: $location")
      }
    }
  }

}
