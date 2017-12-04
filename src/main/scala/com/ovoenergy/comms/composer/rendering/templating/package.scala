package com.ovoenergy.comms.composer.rendering

import com.ovoenergy.comms.composer.rendering.templating.SMSTemplateRendering.valueToMap
import com.ovoenergy.comms.model.{CustomerAddress, CustomerProfile, TemplateData}

package object templating {

  case class EmailTemplateData(templateData: Map[String, TemplateData],
                               customerProfile: Option[CustomerProfile],
                               recipientEmailAddress: String)
  case class SMSTemplateData(templateData: Map[String, TemplateData],
                             customerProfile: Option[CustomerProfile],
                             recipientPhoneNumber: String)
  case class PrintTemplateData(templateData: Map[String, TemplateData],
                               customerProfile: Option[CustomerProfile],
                               customerAddress: CustomerAddress)

  type TD = Map[String, TemplateData]

  implicit val canBuildPrintTemplateData = new CanBuildTemplateData[PrintTemplateData] {
    override def buildTemplateData(a: PrintTemplateData): Map[String, TemplateData] = ???
  }

  implicit val canBuildSMSTemplateData = new CanBuildTemplateData[SMSTemplateData] {

    override def buildTemplateData(a: SMSTemplateData): Map[String, TemplateData] = {

      val customerProfileMap: Map[String, Map[String, String]] = a.customerProfile
        .map(profile => Map("profile" -> valueToMap(profile)))
        .getOrElse(Map.empty)

        // TODO writea valueToTemplateData

      ???
    }
  }
//  val customerProfileMap = customerProfile
//    .map(profile => Map("profile" -> valueToMap(profile)))
//    .getOrElse(Map.empty)
//
//  val phoneNumberMap = Map("recipient" -> Map("phoneNumber" -> recipientPhoneNumber))
//
//  val customerData = Monoid.combine(customerProfileMap, phoneNumberMap)

  implicit val canBuildEmailTemplateData = new CanBuildTemplateData[EmailTemplateData] {
    override def buildTemplateData(a: EmailTemplateData): Map[String, TemplateData] = ???
  }

  //    val emailAddressMap: Map[String, Map[String, String]] = Map(
  //      "recipient" -> Map("emailAddress" -> recipientEmailAddress))
  //
  //    val customerProfileMap: Map[String, Map[String, String]] = customerProfile
  //      .map { c =>
  //        Map("profile" -> valueToMap(c))
  //      }
  //      .getOrElse(Map.empty[String, Map[String, String]])
  //
  //    val customerData: Map[String, Map[String, String]] =
  //      Monoid.combine(emailAddressMap, customerProfileMap)

}
