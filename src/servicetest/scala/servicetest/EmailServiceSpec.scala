package com.ovoenergy.comms.composer
package servicetest

import cats.implicits._
import cats.effect.IO

import com.ovoenergy.comms.model._
import email._

import Arbitraries._
class EmailServiceSpec extends ServiceSpec {

  "Composer" should {

    "process orchestrated Email message successfully" in {
      val sourceMessage: OrchestratedEmailV4 = {
        val initial = generate[OrchestratedEmailV4]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }
      positiveTest[OrchestratedEmailV4, ComposedEmailV4](sourceMessage, topics.orchestratedEmail, topics.composedEmail){ message =>

        note(s"Sent message: ${sourceMessage} Received message: ${message}")

        message.record.key() shouldBe sourceMessage.metadata.commId
        message.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
        message.record.value().recipient shouldBe sourceMessage.recipientEmailAddress
      }
    }

    "send a feedback (and failed) message if the template does not exist" in {
      val sourceMessage: OrchestratedEmailV4 = {
        val initial = generate[OrchestratedEmailV4]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }
      negativeTest[OrchestratedEmailV4](sourceMessage, topics.orchestratedEmail){ (failed, feedback) =>

        note(s"Sent message: ${sourceMessage} Received messages: ${failed} ${feedback}")

        failed.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
        failed.record.value().errorCode shouldBe InvalidTemplate

        feedback.record.value().commId shouldBe sourceMessage.metadata.commId
        feedback.record.value().status shouldBe FeedbackOptions.Failed
      }
    }
  }
}
