package com.ovoenergy.comms.composer

import java.time._

import cats.MonadError
import cats.implicits._
import cats.kernel.Monoid
import java.util.{Map => JMap, HashMap => JHashMap}
import scala.collection.JavaConverters._

import com.ovoenergy.comms.model.{CustomerAddress, CustomerProfile, TemplateData}

package object logic {

  implicit class RichF[F[_], A](fOptA: F[Option[A]]) {
    def orRaiseError(error: Throwable)(implicit me: MonadError[F, Throwable]) = {
      fOptA.flatMap(_.fold(error.raiseError[F, A])(_.pure[F]))
    }
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
      specificData: Map[String, TemplateData]): Map[String, TemplateData] = {
    systemTemplateData(now) ++
      profileTemplateData(profileOpt) ++
      specificData
  }
}
