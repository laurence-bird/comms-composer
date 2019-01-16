package com.ovoenergy.comms.composer
package servicetest


import cats.implicits._
import cats.effect.IO

import com.ovoenergy.comms.model._
import print._
import com.ovoenergy.fs2.kafka

class PrintServiceSpec extends ServiceSpec with TestGenerators {

  "Composer" should {
    "process orchestrated print message successfully" in {
      val sourceMessage = {
        val initial = generate[OrchestratedPrintV2]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val record = new ProducerRecord(topics.orchestratedPrint.name, sourceMessage.metadata.commId, sourceMessage)
      val pm = ProducerMessage.single[Id].of(record)

      val message = (for {
        _        <- Stream.eval(uploadTemplateToS3(sourceMessage.metadata.templateManifest))
        producer <- producerS[OrchestratedPrintV2]
        _        <- Stream.eval(givenDocRaptorSucceeds)
        consumer <- consumerS[ComposedPrintV2].evalTap(_.subscribeTo(topics.composedPrint.name))
        _        <- Stream.eval(producer.produce(pm))
        consumed <- consumer.stream.head
      } yield consumed).compile.lastOrError.futureValue

      message.record.key() shouldBe sourceMessage.metadata.commId
      message.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
      message.record.value().pdfIdentifier should not be empty
    }

    "send a feedback (and failed) message if the template does not exist" in {

      val sourceMessage = {
        val initial = generate[OrchestratedPrintV2]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val record = new ProducerRecord(topics.orchestratedPrint.name, sourceMessage.metadata.commId, sourceMessage)
      val pm = ProducerMessage.single[Id].of(record)

      val (failed, feedback) = (for {
        producer <- producerS[OrchestratedPrintV2]
        _        <- Stream.emit(givenDocRaptorSucceeds)
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

    "send a feedback (and failed) message if the DocRaptor fails" in {

      val sourceMessage = {
        val initial = generate[OrchestratedPrintV2]
        initial.copy(
          customerProfile = generate[CustomerProfile].some,
          templateData = Map("amount"->TemplateData.fromString("10000"))
        )
      }

      val record = new ProducerRecord(topics.orchestratedPrint.name, sourceMessage.metadata.commId, sourceMessage)
      val pm = ProducerMessage.single[Id].of(record)

      val (failed, feedback) = (for {
        _        <- Stream.eval(uploadTemplateToS3(sourceMessage.metadata.templateManifest))
        producer <- producerS[OrchestratedPrintV2]
        _        <- Stream.eval(givenDocRaptorFails(404))
        failedConsumer <- consumerS[FailedV3].evalTap(_.subscribeTo(topics.failed.name))
        feedbackConsumer <- consumerS[Feedback].evalTap(_.subscribeTo(topics.feedback.name))
        _        <- Stream.eval(producer.produce(pm))
        failed <- failedConsumer.stream.take(2).last.map(_.get)
        feedback <- feedbackConsumer.stream.take(2).last.map(_.get)
      } yield (failed, feedback)).compile.lastOrError.futureValue

      failed.record.value().metadata.commId shouldBe sourceMessage.metadata.commId
      failed.record.value().errorCode shouldBe CompositionError

      feedback.record.value().commId shouldBe sourceMessage.metadata.commId
      feedback.record.value().status shouldBe FeedbackOptions.Failed
    }
  }

}
