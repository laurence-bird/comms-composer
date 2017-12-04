package com.ovoenergy.comms.composer.rendering

import cats.kernel.{Monoid, Semigroup}
import cats.implicits._
import com.ovoenergy.comms.composer.rendering.templating.SMSTemplateRendering.valueToMap
import com.ovoenergy.comms.model.{CustomerAddress, CustomerProfile, TemplateData}
import shapeless.ops.hlist.ToTraversable
import shapeless.ops.record.Fields
import shapeless.{HList, LabelledGeneric}

package object templating extends Rendering {

  case class EmailTemplateData(templateData: Map[String, TemplateData],
                               customerProfile: Option[CustomerProfile],
                               recipientEmailAddress: String)

  case class SMSTemplateData(templateData: Map[String, TemplateData],
                             customerProfile: Option[CustomerProfile],
                             recipientPhoneNumber: String)

  case class PrintTemplateData(templateData: Map[String, TemplateData],
                               customerProfile: Option[CustomerProfile],
                               customerAddress: CustomerAddress)

  implicit val canBuildSMSTemplateData = new BuildHandlebarsData[SMSTemplateData] {

    override def apply(a: SMSTemplateData): HandlebarsData = {

      val customerProfileMap = a.customerProfile
        .map(profile => Map("profile" -> valueToMap(profile)))
        .getOrElse(Map.empty)

      val phoneNumberMap = Map("recipient" -> Map("phoneNumber" -> a.recipientPhoneNumber))

      val customerData = Monoid.combine(customerProfileMap, phoneNumberMap)

      HandlebarsData(a.templateData, customerData)
    }
  }

  implicit val canBuildEmailTemplateData = new BuildHandlebarsData[EmailTemplateData] {
    override def apply(a: EmailTemplateData): HandlebarsData = {

      val emailAddressMap: Map[String, Map[String, String]] = Map(
        "recipient" -> Map("emailAddress" -> a.recipientEmailAddress))

      val customerProfileMap: Map[String, Map[String, String]] = a.customerProfile
        .map { c =>
          Map("profile" -> valueToMap(c))
        }
        .getOrElse(Map.empty[String, Map[String, String]])

      val customerData: Map[String, Map[String, String]] = Monoid.combine(emailAddressMap, customerProfileMap)

      HandlebarsData(a.templateData, customerData)
    }
  }

  implicit val canBuildPrintTemplateData = new BuildHandlebarsData[PrintTemplateData] {
    override def apply(a: PrintTemplateData): HandlebarsData = {

      val addressMap = Map(
          "line1" -> Some(a.customerAddress.line1),
          "town" -> Some(a.customerAddress.town),
          "postcode" -> Some(a.customerAddress.postcode),
          "line2" -> a.customerAddress.line2,
          "county" -> a.customerAddress.county,
          "country" -> a.customerAddress.country
        ) collect { case (k, Some(v)) => (k, v) }

      val customerAddressMap: Map[String, Map[String, String]] = Map("address" -> addressMap)

      val customerProfileMap: Map[String, Map[String, String]] = a.customerProfile
        .map { c =>
          Map("profile" -> valueToMap(c))
        }
        .getOrElse(Map.empty[String, Map[String, String]])

      val customerData: Map[String, Map[String, String]] = Monoid.combine(customerAddressMap, customerProfileMap)

      HandlebarsData(a.templateData, customerData)
    }
  }

  implicit val canBuildTemplateData = new BuildHandlebarsData[Map[String, TemplateData]] {
    override def apply(a: Map[String, TemplateData]) =
      HandlebarsData(a, Map.empty[String, Map[String, String]])
  }

  def valueToTemplateData[E, L <: HList, F <: HList](instanceToConvert: E)(
      implicit gen: LabelledGeneric.Aux[E, L],
      fields: Fields.Aux[L, F],
      toTraversableAux: ToTraversable.Aux[F, List, (Symbol, String)]): TemplateData = {

    val fieldsHlist = fields.apply(gen.to(instanceToConvert))
    val fieldsList = toTraversableAux(fieldsHlist)

    val r = fieldsList.map {
      case (sym, value) => (sym.name, TemplateData.fromString(value))
    }.toMap

    TemplateData.fromMap(r)
  }

}
