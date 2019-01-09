package com.ovoenergy.comms.composer
package servicetest

import cats.Id
import com.ovoenergy.comms.model._
import sms._
import cats.implicits._
import cats.effect.{IO, Timer}
import com.ovoenergy.comms.composer.kafka.Kafka
import fs2._
import fs2.kafka._
import org.apache.kafka.clients.producer.ProducerRecord
import shapeless.record

import scala.concurrent.ExecutionContext


class SmsServiceSpec extends ServiceSpec with TestGenerators {

  "Composer" should {
    "process orchestrated sms message successfully" in {

      val sourceMessage = {
        val initial = generate[OrchestratedSMSV3]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val record = new ProducerRecord(topics.orchestratedSms.name, sourceMessage.metadata.commId, sourceMessage)
      val pm = ProducerMessage.single[Id].of(record)


      val message: CommittableMessage[IO, String, ComposedSMSV4] = (for {
        _        <- Stream.eval(uploadTemplateToS3(sourceMessage.metadata.templateManifest))
        producer <- producerS[OrchestratedSMSV3]
        consumer <- consumerS[ComposedSMSV4].evalTap(_.subscribeTo(topics.composedSms.name))
        _        <- Stream.eval(producer.produce(pm))
        consumed <- consumer.stream.head
      } yield consumed).compile.lastOrError.futureValue

      message.record.key() shouldBe sourceMessage.metadata.commId
      message.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
      message.record.value().recipient shouldBe sourceMessage.recipientPhoneNumber
    }


    "send a feedback (and failed) message if the template does not exist" in {

      val sourceMessage = {
        val initial = generate[OrchestratedSMSV3]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val record = new ProducerRecord(topics.orchestratedSms.name, sourceMessage.metadata.commId, sourceMessage)
      val pm = ProducerMessage.single[Id].of(record)

      val (failed, feedback) = (for {
        _        <- Stream.eval(uploadTemplateToS3(sourceMessage.metadata.templateManifest))
        producer <- producerS[OrchestratedSMSV3]
        failedConsumer <- consumerS[FailedV3].evalTap(_.subscribeTo(topics.failed.name))
        feedbackConsumer <- consumerS[Feedback].evalTap(_.subscribeTo(topics.feedback.name))
        _        <- Stream.eval(producer.produce(pm))
        failed <- failedConsumer.stream.head
        feedback <- feedbackConsumer.stream.head
      } yield (failed, feedback)).compile.lastOrError.futureValue

      failed.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
      failed.record.value().errorCode shouldBe InvalidTemplate

      feedback.record.value().commId shouldBe sourceMessage.metadata.commId
      feedback.record.value().status shouldBe FeedbackOptions.Failed
    }
  }

}
