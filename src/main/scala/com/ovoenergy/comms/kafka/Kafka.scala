package com.ovoenergy.comms.kafka

import akka.Done
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.Logging
import com.ovoenergy.comms.model.{ComposedEmail, Failed, OrchestratedEmail}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Kafka extends Logging {

  case class Input[T](topic: String, consumerSettings: ConsumerSettings[String, T])
  case class Output[T](topic: String, producer: KafkaProducer[String, T])

  def buildStream(input: Input[Option[OrchestratedEmail]],
                  composedEmailEventOutput: Output[ComposedEmail],
                  failedEventOutput: Output[Failed])(
      processEvent: OrchestratedEmail => Either[Failed, ComposedEmail]): Source[Done, Control] = {

    def sendComposedEmail(composedEmail: ComposedEmail): Future[RecordMetadata] = {
      info(composedEmail.metadata.traceToken)(s"Sending ComposedEmail event. Metadata: ${composedEmail.metadata}")
      composedEmailEventOutput.producer.send(
        new ProducerRecord(composedEmailEventOutput.topic, composedEmail.metadata.customerId, composedEmail))
    }

    def sendFailed(failed: Failed): Future[RecordMetadata] = {
      info(failed.metadata.traceToken)(s"Sending Failed event: $failed")
      failedEventOutput.producer.send(new ProducerRecord(failedEventOutput.topic, failed.metadata.customerId, failed))
    }

    Consumer.committableSource(input.consumerSettings, Subscriptions.topics(input.topic)).mapAsync(1) { msg =>
      val future: Future[_] = msg.record.value match {
        case Some(orchestratedEmail) =>
          info(orchestratedEmail.metadata.traceToken)(s"Processing event: $orchestratedEmail")
          processEvent(orchestratedEmail) match {
            case Left(failed) => sendFailed(failed)
            case Right(result) => sendComposedEmail(result)
          }
        case None =>
          Future.successful(())
      }
      future flatMap { _ =>
        msg.committableOffset.commitScaladsl()
      }
    }
  }

}
