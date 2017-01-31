package com.ovoenergy.comms.kafka

import akka.Done
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import akka.stream.{ActorAttributes, Supervision}
import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.Logging
import com.ovoenergy.comms.kafka.ComposerStream.{Input, Output}
import com.ovoenergy.comms.model.{ComposedEmail, Failed, OrchestratedEmail, OrchestratedEmailV2}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ComposerStreamV1 extends Logging {

  def build(input: Input[Option[OrchestratedEmail]],
            orchestratedEmailConverter: OrchestratedEmail => OrchestratedEmailV2,
            composedEmailEventOutput: => Output[ComposedEmail],
            failedEventOutput: => Output[Failed])(
      processEvent: OrchestratedEmailV2 => Either[Failed, ComposedEmail]): Source[Done, Control] = {

    def sendComposedEmail(composedEmail: ComposedEmail): Future[RecordMetadata] = {
      info(composedEmail.metadata.traceToken)(s"Sending ComposedEmail event. Metadata: ${composedEmail.metadata}")
      composedEmailEventOutput.producer.send(
        new ProducerRecord(composedEmailEventOutput.topic, composedEmail.metadata.customerId, composedEmail))
    }

    def sendFailed(failed: Failed): Future[RecordMetadata] = {
      info(failed.metadata.traceToken)(s"Sending Failed event: $failed")
      failedEventOutput.producer.send(new ProducerRecord(failedEventOutput.topic, failed.metadata.customerId, failed))
    }

    val decider: Supervision.Decider = { e =>
      log.error("Kafka consumer actor exploded!", e)
      Supervision.Stop
    }

    Consumer
      .committableSource(input.consumerSettings, Subscriptions.topics(input.topic))
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .mapAsync(1) { msg =>
        val future: Future[_] = msg.record.value match {
          case Some(orchestratedEmail) =>
            val orchestratedEmailV2 = orchestratedEmailConverter(orchestratedEmail)
            info(orchestratedEmail.metadata.traceToken)(s"Processing event: $orchestratedEmailV2")
            processEvent(orchestratedEmailV2) match {
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
