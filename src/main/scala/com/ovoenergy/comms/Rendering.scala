package com.ovoenergy.comms

import java.io.IOException
import java.time.{Clock, ZonedDateTime}
import java.util.{Map => JMap}

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.instances.option._
import cats.syntax.traverse._
import cats.{Apply, Id, Semigroup}
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.{AbstractTemplateLoader, StringTemplateSource, TemplateLoader, TemplateSource}
import com.github.jknack.handlebars.{Handlebars, Helper, Options}
import com.ovoenergy.comms.email.RenderedEmail
import com.ovoenergy.comms.model.ErrorCode.{InvalidTemplate, MissingTemplateData}
import com.ovoenergy.comms.model.TemplateData.TD
import com.ovoenergy.comms.model.{CommManifest, CustomerProfile, ErrorCode, TemplateData}
import com.ovoenergy.comms.templates.model.HandlebarsTemplate
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import shapeless.{Inl, Inr, LabelledGeneric}

import scala.collection.JavaConverters._
import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

object Rendering extends Logging {

  private sealed trait FragmentType

  private object FragmentType {

    case object Html extends FragmentType

    case object Text extends FragmentType

  }

  case class RenderingErrors(reason: String, errorCode: ErrorCode)
  private final case class Errors(missingKeys: Set[String], exceptions: Seq[Throwable], errorCode: ErrorCode) {
    def toErrorMessage: String = {
      val missingKeysMsg = {
        if (missingKeys.nonEmpty)
          s"""The template referenced the following non-existent keys:
              |${missingKeys.map(k => s" - $k").mkString("\n")}
           """.stripMargin
        else
          ""
      }
      val exceptionsMsg = {
        if (exceptions.nonEmpty)
          s"""The following exceptions were thrown:
              |${exceptions.map(e => s" - ${e.getMessage}").mkString("\n")}
           """.stripMargin
        else
          ""
      }
      s"$missingKeysMsg$exceptionsMsg"
    }
  }
  private object Errors {
    implicit val semigroup: Semigroup[Errors] = new Semigroup[Errors] {
      override def combine(x: Errors, y: Errors): Errors =
        Errors(x.missingKeys ++ y.missingKeys, x.exceptions ++ y.exceptions, x.errorCode)
    }
  }

  private type ErrorsOr[A] = Validated[Errors, A]

  def renderEmail(clock: Clock)(commManifest: CommManifest,
                                template: EmailTemplate[Id],
                                data: Map[String, TemplateData],
                                customerProfile: CustomerProfile,
                                recipientEmailAddress: String): Either[RenderingErrors, RenderedEmail] = {

    def extractValueFromTemplateData(templateData: TemplateData): AnyRef = {
      templateData.value match {
        case (Inl(stringValue)) => stringValue
        case (Inr(Inl(sequence))) => sequence.map(extractValueFromTemplateData).asJava
        case (Inr(Inr(Inl(map)))) =>
          map.map({ case (key, value) => key -> extractValueFromTemplateData(value) }).asJava
        case (Inr(Inr(Inr(_)))) => throw new Exception("Unable to extract value from template data")
      }
    }

    val dataAsStrings = data.map({
      case (key, templateData) => key -> extractValueFromTemplateData(templateData)
    })

    val context: JMap[String, AnyRef] = (dataAsStrings +
      ("profile" -> profileToMap(customerProfile)) +
      ("recipient" -> Map("emailAddress" -> recipientEmailAddress).asJava) +
      ("system" -> systemVariables(clock))).asJava

    val subject: ErrorsOr[String] = {
      val handlebars = new HandlebarsWrapper()
      val filename = buildFilename(commManifest, "subject")
      handlebars.render(filename, template.subject)(context)
    }
    val htmlBody: ErrorsOr[String] = {
      val handlebars = new HandlebarsWrapper()
      val filename = buildFilename(commManifest, "htmlBody")
      handlebars.render(filename, template.htmlBody)(context)
    }
    val textBody: Option[ErrorsOr[String]] =
      template.textBody map { tb =>
        val handlebars = new HandlebarsWrapper()
        val filename = buildFilename(commManifest, "textBody")
        handlebars.render(filename, tb)(context)
      }

    val missingKeysOrResult: ErrorsOr[RenderedEmail] =
      Apply[ErrorsOr].map3(subject, htmlBody, textBody.sequenceU) {
        case (s, h, t) => RenderedEmail(s, h, t)
      }

    missingKeysOrResult.leftMap(errors => RenderingErrors(errors.toErrorMessage, errors.errorCode)).toEither
  }

  private def systemVariables(clock: Clock): JMap[String, String] = {
    val now = ZonedDateTime.now(clock)
    Map(
      "year" -> now.getYear.toString,
      "month" -> now.getMonth.getValue.toString,
      "dayOfMonth" -> now.getDayOfMonth.toString
    ).asJava
  }

  /*
   Builds a "filename" for a Mustache template.
   This is not actually a filename. It's actually a key for use by the template cache.

   In fact we are not using a template cache, so the filename is not even used as a cache key,
   but it's still nice to have a unique, human-readable identifier for a Mustache template.
   */
  private def buildFilename(commManifest: CommManifest, suffixes: String*): String =
    (Seq(commManifest.commType, commManifest.name, commManifest.version) ++ suffixes).mkString("::")

  /*
  Use shapeless to turn the CustomerProfile case class into a Map[String, String]
   */
  private def profileToMap(profile: CustomerProfile): JMap[String, String] = {
    import shapeless.ops.record._
    val generic = LabelledGeneric[CustomerProfile]
    val fieldsHlist = Fields[generic.Repr].apply(generic.to(profile))
    val fieldsList = fieldsHlist.toList[(Symbol, String)]
    fieldsList
      .map {
        case (sym, value) => (sym.name, value)
      }
      .toMap
      .asJava
  }
  /*
  Wrapper for Handlebars that keeps track of any references to missing keys
   */
  private class HandlebarsWrapper() {
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
    private val handlebars = new Handlebars().`with`(helperRegistry)

    def render(filename: String, template: HandlebarsTemplate)(context: JMap[String, AnyRef]): ErrorsOr[String] = {
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

}
