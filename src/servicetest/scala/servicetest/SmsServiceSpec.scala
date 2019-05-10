package com.ovoenergy.comms.composer
package servicetest

import cats.implicits._
import cats.effect._

import com.ovoenergy.comms.model._
import sms._

import Arbitraries._

class SmsServiceSpec extends ServiceSpec {

  "Composer" should {

    "process orchestrated sms message successfully" in {
      val sourceMessage: OrchestratedSMSV3 = {
        val initial = generate[OrchestratedSMSV3]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      positiveTest[OrchestratedSMSV3, ComposedSMSV4](sourceMessage, topics.orchestratedSms, topics.composedSms){message =>

        note(s"Sent message: ${sourceMessage} Received message: ${message}")

        message.record.key() shouldBe sourceMessage.metadata.commId
        message.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
        message.record.value().recipient shouldBe sourceMessage.recipientPhoneNumber
      }
    }

    "send a feedback (and failed) message if the template does not exist" in {
      val sourceMessage: OrchestratedSMSV3 = {
        val initial = generate[OrchestratedSMSV3]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      negativeTest[OrchestratedSMSV3](sourceMessage, topics.orchestratedSms){(failed, feedback) =>

        note(s"Sent message: ${sourceMessage} Received messages: ${failed} ${feedback}")

        failed.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
        failed.record.value().errorCode shouldBe InvalidTemplate

        feedback.record.value().commId shouldBe sourceMessage.metadata.commId
        feedback.record.value().status shouldBe FeedbackOptions.Failed
      }
    }
  }
}
