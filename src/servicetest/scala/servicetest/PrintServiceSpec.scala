//package com.ovoenergy.comms.composer
//package servicetest
//
//import com.ovoenergy.comms.model._
//import print._
//import com.ovoenergy.fs2.kafka
//
//import cats.implicits._
//import cats.effect.IO
//
//class PrintServiceSpec extends ServiceSpec with TestGenerators {
//
//  "Composer" should {
//    "process orchestrated print message successfully" in {
//
//      val sourceMessage = {
//        val initial = generate[OrchestratedPrintV2]
//        initial.copy(
//          customerProfile = generate[CustomerProfile].some,
//          templateData = Map("amount"->TemplateData.fromString("10000"))
//        )
//      }
//
//      val consumed = withProducerFor(topics.orchestratedPrint) { producer =>
//        for {
//          _ <- uploadTemplateToS3(
//            sourceMessage.metadata.templateManifest
//          )
//          _ <- givenDocRaptorSucceeds
//          _ <- kafka.produceRecord[IO](
//            producer,
//            producerRecord(topics.orchestratedPrint)(sourceMessage, _.metadata.commId)
//          )
//          record <- consume(topics.composedPrint)(r => r.pure[IO]).head.compile.lastOrRethrow
//        } yield record
//      }.futureValue
//
//      consumed.value().metadata.commId shouldBe sourceMessage.metadata.commId
//      consumed.value().pdfIdentifier should not be empty
//    }
//
//    "send a feedback (and failed) message if the template does not exist" in {
//
//      val sourceMessage = {
//        val initial = generate[OrchestratedPrintV2]
//        initial.copy(
//          customerProfile = generate[CustomerProfile].some,
//          templateData = Map("amount"->TemplateData.fromString("10000"))
//        )
//      }
//
//      val (failed, feedback) = withProducerFor(topics.orchestratedPrint) { producer =>
//        for {
//          _ <- givenDocRaptorSucceeds
//          _ <- kafka.produceRecord[IO](
//            producer,
//            producerRecord(topics.orchestratedPrint)(sourceMessage, _.metadata.commId)
//          )
//          failedRecord <- consume(topics.failed)(r => r.pure[IO]).head.compile.lastOrError
//          feedbackRecord <- consume(topics.feedback)(r => r.pure[IO]).head.compile.lastOrError
//        } yield (failedRecord, feedbackRecord)
//      }.futureValue
//
//      failed.value().metadata.commId shouldBe sourceMessage.metadata.commId
//      failed.value().errorCode shouldBe InvalidTemplate
//
//      feedback.value().commId shouldBe sourceMessage.metadata.commId
//      feedback.value().status shouldBe FeedbackOptions.Failed
//    }
//
//    "send a feedback (and failed) message if the docraptor fails" in {
//
//      val sourceMessage = {
//        val initial = generate[OrchestratedPrintV2]
//        initial.copy(
//          customerProfile = generate[CustomerProfile].some,
//          templateData = Map("amount"->TemplateData.fromString("10000"))
//        )
//      }
//
//      val (failed, feedback) = withProducerFor(topics.orchestratedPrint) { producer =>
//        for {
//          _ <- uploadTemplateToS3(
//            sourceMessage.metadata.templateManifest
//          )
//          _ <- givenDocRaptorFails(404)
//          _ <- kafka.produceRecord[IO](
//            producer,
//            producerRecord(topics.orchestratedPrint)(sourceMessage, _.metadata.commId)
//          )
//          failedRecord <- consume(topics.failed)(r => r.pure[IO]).head.compile.lastOrError
//          feedbackRecord <- consume(topics.feedback)(r => r.pure[IO]).head.compile.lastOrError
//        } yield (failedRecord, feedbackRecord)
//      }.futureValue
//
//      failed.value().metadata.commId shouldBe sourceMessage.metadata.commId
//      failed.value().errorCode shouldBe CompositionError
//
//      feedback.value().commId shouldBe sourceMessage.metadata.commId
//      feedback.value().status shouldBe FeedbackOptions.Failed
//    }
//  }
//
//}
