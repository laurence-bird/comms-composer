//package com.ovoenergy.comms.composer
//package servicetest
//
//import cats.Id
//import com.ovoenergy.comms.model._
//import sms._
//import cats.implicits._
//import cats.effect.{IO, Timer}
//import com.ovoenergy.comms.composer.kafka.Kafka
//import fs2.kafka._
//import org.apache.kafka.clients.producer.ProducerRecord
//import shapeless.record
//
//import scala.concurrent.ExecutionContext
//
//
//class SmsServiceSpec extends ServiceSpec with TestGenerators {
//
//  "Composer" should {
//    "process orchestrated sms message successfully" in {
//
//      val sourceMessage = {
//        val initial = generate[OrchestratedSMSV3]
//        initial.copy(
//          customerProfile = generate[CustomerProfile].some,
//          templateData = Map("amount"->TemplateData.fromString("10000"))
//        )
//      }
//
////      val consumed = withProducerFor(topics.orchestratedSms) { producer =>
//      ////        for {
//      ////          _ <- uploadTemplateToS3(
//      ////            sourceMessage.metadata.templateManifest
//      ////          )
//      ////          _ <- producerStream
//      //////            kafka.produceRecord[IO](
//      //////            producer,
//      //////            producerRecord(topics.orchestratedSms)(sourceMessage, _.metadata.commId)
//      ////         )
//      ////          record <- consume(topics.composedSms)(r => r.pure[IO]).head.compile.lastOrError
//      ////        } yield record
//      ////      }.futureValue
//
//      val record = new ProducerRecord(topics.orchestratedSms.name, sourceMessage.metadata.commId, sourceMessage)
//      val pm = ProducerMessage.single[Id].of(record)
//
//      producerS[OrchestratedSMSV3].map(_.produce(pm))
//
//      consumerS[ComposedSMSV4]
//        .evalTap(_.subscribeTo(topics.composedSms.name))
//        .map(_.stream.map{result =>
//          result.record.value.metadata.commId shouldBe sourceMessage.metadata.commId
//          result.record.value.recipient shouldBe sourceMessage.recipientPhoneNumber
//        })
//
//    }
//
//    "send a feedback (and failed) message if the template does not exist" in {
//
//      val sourceMessage = {
//        val initial = generate[OrchestratedSMSV3]
//        initial.copy(
//          customerProfile = generate[CustomerProfile].some,
//          templateData = Map("amount"->TemplateData.fromString("10000"))
//        )
//      }
//
//      val (failed, feedback) = withProducerFor(topics.orchestratedSms) { producer =>
//        for {
//          _ <- kafka.produceRecord[IO](
//            producer,
//            producerRecord(topics.orchestratedSms)(sourceMessage, _.metadata.commId)
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
//  }
//
//}
