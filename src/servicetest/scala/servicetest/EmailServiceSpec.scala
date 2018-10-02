package com.ovoenergy.comms.composer
package servicetest

import com.ovoenergy.comms.model._
import email.{OrchestratedEmailV4, ComposedEmailV4}

import com.ovoenergy.fs2.kafka

import cats.implicits._
import cats.effect.IO

import org.apache.kafka.clients.producer.ProducerRecord

class EmailServiceSpec extends ServiceSpec with TestGenerators {

  "Composer" should {
    "process orchestrated email message successfully" in {

      val sourceMessage = {
        val initial = generate[OrchestratedEmailV4]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val consumed = withProducerFor(topics.orchestratedEmail) { producer =>
        for {
          _ <- uploadTemplateToS3(
            sourceMessage.metadata.templateManifest
          )
          _ <- kafka.produceRecord[IO](
            producer,
            new ProducerRecord[String, OrchestratedEmailV4](
              topics.orchestratedEmail.name,
              sourceMessage.metadata.commId,
              sourceMessage)
          )
          record <- consume(topics.composedEmail)(r => r.pure[IO]).head.compile.lastOrRethrow
        } yield record
      }.futureValue

      consumed.value().metadata.commId shouldBe sourceMessage.metadata.commId
      consumed.value().recipient shouldBe sourceMessage.recipientEmailAddress
    }

    "send a feedback (and failed) message if the template does not exist" in {

      val sourceMessage = {
        val initial = generate[OrchestratedEmailV4]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val (failed, feedback) = withProducerFor(topics.orchestratedEmail) { producer =>
        for {
          _ <- kafka.produceRecord[IO](
            producer,
            new ProducerRecord[String, OrchestratedEmailV4](
              topics.orchestratedEmail.name,
              sourceMessage.metadata.commId,
              sourceMessage)
          )
          failedRecord <- consume(topics.failed)(r => r.pure[IO]).head.compile.lastOrRethrow
          feedbackRecord <- consume(topics.feedback)(r => r.pure[IO]).head.compile.lastOrRethrow
        } yield (failedRecord, feedbackRecord)
      }.futureValue

      failed.value().metadata.commId shouldBe sourceMessage.metadata.commId
      failed.value().errorCode shouldBe TemplateDownloadFailed

      feedback.value().commId shouldBe sourceMessage.metadata.commId
      feedback.value().status shouldBe FeedbackOptions.Failed
    }
  }

}
