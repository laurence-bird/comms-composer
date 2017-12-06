package com.ovoenergy.comms.composer.rendering

import cats.kernel.{Monoid, Semigroup}
import cats.implicits._
import com.ovoenergy.comms.composer.rendering.templating.SMSTemplateRendering.valueToMap
import com.ovoenergy.comms.model.{CustomerAddress, CustomerProfile, TemplateData}
import shapeless.ops.hlist.ToTraversable
import shapeless.ops.record.Fields
import shapeless.{HList, LabelledGeneric}

package object templating extends Rendering {

  sealed trait CommTemplateData {
    def buildHandlebarsData: HandlebarsData
  }

  case class EmailTemplateData(templateData: Map[String, TemplateData],
                               customerProfile: Option[CustomerProfile],
                               recipientEmailAddress: String)
      extends CommTemplateData {
    override def buildHandlebarsData: HandlebarsData = {
      val emailAddressMap: Map[String, Map[String, String]] = Map(
        "recipient" -> Map("emailAddress" -> recipientEmailAddress))

      val customerProfileMap: Map[String, Map[String, String]] = customerProfile
        .map { c =>
          Map("profile" -> valueToMap(c))
        }
        .getOrElse(Map.empty[String, Map[String, String]])

      val customerData: Map[String, Map[String, String]] = Monoid.combine(emailAddressMap, customerProfileMap)

      HandlebarsData(templateData, customerData)
    }
  }

  case class SMSTemplateData(templateData: Map[String, TemplateData],
                             customerProfile: Option[CustomerProfile],
                             recipientPhoneNumber: String)
      extends CommTemplateData {
    override def buildHandlebarsData: HandlebarsData = {
      val customerProfileMap = customerProfile
        .map(profile => Map("profile" -> valueToMap(profile)))
        .getOrElse(Map.empty)

      val phoneNumberMap = Map("recipient" -> Map("phoneNumber" -> recipientPhoneNumber))

      val customerData = Monoid.combine(customerProfileMap, phoneNumberMap)

      HandlebarsData(templateData, customerData)
    }
  }

  case class PrintTemplateData(templateData: Map[String, TemplateData],
                               customerProfile: Option[CustomerProfile],
                               customerAddress: CustomerAddress)
      extends CommTemplateData {
    override def buildHandlebarsData: HandlebarsData = {

      val addressMap = Map(
          "line1" -> Some(customerAddress.line1),
          "town" -> Some(customerAddress.town),
          "postcode" -> Some(customerAddress.postcode),
          "line2" -> customerAddress.line2,
          "county" -> customerAddress.county,
          "country" -> customerAddress.country
        ) collect { case (k, Some(v)) => (k, v) }

      val customerAddressMap: Map[String, Map[String, String]] = Map("address" -> addressMap)

      val customerProfileMap: Map[String, Map[String, String]] = customerProfile
        .map { c =>
          Map("profile" -> valueToMap(c))
        }
        .getOrElse(Map.empty[String, Map[String, String]])

      val customerData: Map[String, Map[String, String]] = Monoid.combine(customerAddressMap, customerProfileMap)

      HandlebarsData(templateData, customerData)
    }
  }

  case class TemplateDataWrapper(templateData: Map[String, TemplateData]) extends CommTemplateData {
    override def buildHandlebarsData: HandlebarsData =
      HandlebarsData(templateData, Map.empty[String, Map[String, String]])
  }
}
