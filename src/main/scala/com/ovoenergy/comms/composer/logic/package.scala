package com.ovoenergy.comms.composer

import java.time._

import cats.MonadError
import cats.implicits._
import cats.kernel.Monoid
import java.util.{Map => JMap, HashMap => JHashMap}
import scala.collection.JavaConverters._

import com.ovoenergy.comms.model.{CustomerAddress, CustomerProfile, TemplateData, TemplateManifest}
import model.{TemplateFragmentId, TemplateFragmentType}

package object logic {

  implicit class RichF[F[_], A](fOptA: F[Option[A]]) {
    def orRaiseError(error: Throwable)(implicit me: MonadError[F, Throwable]) = {
      fOptA.flatMap(_.fold(error.raiseError[F, A])(_.pure[F]))
    }
  }

  // TODO I am not a fan of it, I would like to have this information on DynamoTable instead
  def templateFragmentIdFor(
      manifest: TemplateManifest,
      fragmentType: TemplateFragmentType): TemplateFragmentId = fragmentType match {
    case TemplateFragmentType.Email.Sender =>
      TemplateFragmentId("${manifest.id}/${manifest.version}/email/sender.txt")
    case TemplateFragmentType.Email.Subject =>
      TemplateFragmentId("${manifest.id}/${manifest.version}/email/subject.txt")
    case TemplateFragmentType.Email.HtmlBody =>
      TemplateFragmentId("${manifest.id}/${manifest.version}/email/body.html")
    case TemplateFragmentType.Email.TextBody =>
      TemplateFragmentId("${manifest.id}/${manifest.version}/email/body.txt")
    case TemplateFragmentType.Sms.Body =>
      TemplateFragmentId("${manifest.id}/${manifest.version}/sms/body.txt")
    case TemplateFragmentType.Print.Body =>
      TemplateFragmentId("${manifest.id}/${manifest.version}/print/body.html")
  }

  // TODO: We can think to write a merge fucntion for template data, having a monoid for it in fact.

  def systemTemplateData(now: Instant): Map[String, TemplateData] = {
    val dateTime: ZonedDateTime = now.atZone(ZoneId.of("UTC"))

    Map(
      "system" -> TemplateData.fromMap(
        Map(
          "year" -> TemplateData.fromString(dateTime.getYear.toString),
          "month" -> TemplateData.fromString(dateTime.getMonth.getValue.toString),
          "dayOfMonth" -> TemplateData.fromString(dateTime.getDayOfMonth.toString),
        )
      )
    )
  }

  def profileTemplateData(profileOpt: Option[CustomerProfile]): Map[String, TemplateData] =
    profileOpt
      .map { profile =>
        Map(
          "profile" ->
            TemplateData.fromMap(
              Map(
                "firstName" -> TemplateData.fromString(profile.firstName),
                "lastName" -> TemplateData.fromString(profile.lastName)
              ))
        )
      }
      .getOrElse(Map.empty)

  def buildTemplateData(
      now: Instant,
      profileOpt: Option[CustomerProfile],
      recipientData: Map[String, TemplateData],
      specificData: Map[String, TemplateData]
  ): TemplateData = {
    TemplateData.fromMap(
      systemTemplateData(now) ++
        profileTemplateData(profileOpt) ++
        recipientData ++
        specificData
    )
  }
}
