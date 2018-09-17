package com.ovoenergy.comms.composer.rendering

import com.google.common.io.BaseEncoding
import com.ovoenergy.comms.model._

sealed trait HashData
case class EmailHashData(deliverTo: DeliverTo,
                         templateData: Map[String, TemplateData],
                         templateManifest: TemplateManifest)
    extends HashData
case class SmsHashData(deliverTo: DeliverTo,
                       templateData: Map[String, TemplateData],
                       templateManifest: TemplateManifest)
    extends HashData
case class PrintHashData(customerProfile: Option[CustomerProfile],
                         customerAddress: CustomerAddress,
                         templateData: Map[String, TemplateData],
                         templateManifest: TemplateManifest)
    extends HashData

case class EventId(value: String) extends HashData

object HashFactory {

  def getHashedComm(hashData: HashData): String = {
    BaseEncoding
      .base32()
      .omitPadding()
      .encode(hashData.toString.getBytes())
      .toLowerCase
  }
}
