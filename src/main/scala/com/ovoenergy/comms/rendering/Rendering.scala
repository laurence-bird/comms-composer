package com.ovoenergy.comms.rendering

import java.time.{Clock, ZonedDateTime}
import java.util.{Map => JMap}

import cats.instances.option._
import cats.syntax.traverse._
import cats.{Apply, Id}
import com.ovoenergy.comms.Logging
import com.ovoenergy.comms.email.RenderedEmail
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.sms.RenderedSMS
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate
import com.ovoenergy.comms.templates.model.template.processed.sms.SMSTemplate
import shapeless.{Inl, Inr, LabelledGeneric}

import scala.collection.JavaConverters._

object Rendering extends Logging {

  case class FailedToRender(reason: String, errorCode: ErrorCode)

  def renderEmail(clock: Clock)(commManifest: CommManifest,
                                template: EmailTemplate[Id],
                                data: Map[String, TemplateData],
                                customerProfile: Option[CustomerProfile],
                                recipientEmailAddress: String): Either[FailedToRender, RenderedEmail] = {

    val context = buildHandlebarsContext(
      data,
      customerProfile,
      Map("emailAddress" -> recipientEmailAddress),
      clock
    )

    val subject: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, Channel.Email, "subject")
      HandlebarsWrapper.render(filename, template.subject)(context)
    }
    val htmlBody: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, Channel.Email, "htmlBody")
      HandlebarsWrapper.render(filename, template.htmlBody)(context)
    }
    val textBody: Option[ErrorsOr[String]] =
      template.textBody map { tb =>
        val filename = buildFilename(commManifest, Channel.Email, "textBody")
        HandlebarsWrapper.render(filename, tb)(context)
      }

    val errorsOrResult: ErrorsOr[RenderedEmail] =
      Apply[ErrorsOr].map3(subject, htmlBody, textBody.sequenceU) {
        case (s, h, t) => RenderedEmail(s, h, t)
      }

    errorsOrResult
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }

  def renderSMS(clock: Clock)(commManifest: CommManifest,
                              template: SMSTemplate[Id],
                              data: Map[String, TemplateData],
                              customerProfile: Option[CustomerProfile],
                              recipientPhoneNumber: String): Either[FailedToRender, RenderedSMS] = {

    val context = buildHandlebarsContext(
      data,
      customerProfile,
      Map("phoneNumber" -> recipientPhoneNumber),
      clock
    )

    val textBody: ErrorsOr[String] = {
      val filename = buildFilename(commManifest, Channel.SMS, "textBody")
      HandlebarsWrapper.render(filename, template.textBody)(context)
    }

    textBody
      .map(RenderedSMS)
      .leftMap(errors => FailedToRender(errors.toErrorMessage, errors.errorCode))
      .toEither
  }

  private def buildHandlebarsContext(data: Map[String, TemplateData],
                                     customerProfile: Option[CustomerProfile],
                                     recipient: Map[String, String],
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

    val dataAsStrings = data map {
      case (key, templateData) => key -> extractValueFromTemplateData(templateData)
    }

    (dataAsStrings +
      ("profile" -> profileToMap(customerProfile)) +
      ("recipient" -> recipient.asJava) +
      ("system" -> systemVariables(clock))).asJava
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
  private def buildFilename(commManifest: CommManifest, channel: Channel, suffixes: String*): String =
    (Seq(commManifest.commType, commManifest.name, commManifest.version, channel.toString) ++ suffixes).mkString("::")

  /*
  Use shapeless to turn the CustomerProfile case class into a Map[String, String]
   */
  import shapeless.ops.record._
  private val customerProfileGen = LabelledGeneric[CustomerProfile]
  private def profileToMap(customerProfile: Option[CustomerProfile]): JMap[String, String] = {
    customerProfile
      .map { profile =>
        val fieldsHlist = Fields[customerProfileGen.Repr].apply(customerProfileGen.to(profile))
        val fieldsList = fieldsHlist.toList[(Symbol, String)]
        fieldsList
          .map {
            case (sym, value) => (sym.name, value)
          }
          .toMap
          .asJava
      }
      .getOrElse(Map().asJava)
  }

}
