package com.ovoenergy.comms.composer.rendering

import java.security.MessageDigest

import com.ovoenergy.comms.model._

sealed trait HashData
case class EmailHashData(deliverTo: DeliverTo, templateData: Map[String, TemplateData], commManifest: CommManifest) extends HashData
case class SmsHashData(deliverTo: DeliverTo, templateData: Map[String, TemplateData], commManifest: CommManifest) extends HashData
case class PrintHashData(customerProfile: Option[CustomerProfile], customerAddress: CustomerAddress, templateData: Map[String, TemplateData], commManifest: CommManifest) extends HashData

object HashFactory {

  def getHashedComm(hashData: HashData): String = {

    val commHash = MessageDigest
      .getInstance("MD5")
      .digest(hashData.toString.getBytes)

    new String(commHash)
  }
}