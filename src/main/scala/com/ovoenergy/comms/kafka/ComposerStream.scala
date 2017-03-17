package com.ovoenergy.comms.kafka

import akka.Done
import akka.actor.Scheduler
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.scaladsl.Source
import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.Logging
import com.ovoenergy.comms.model.{ComposedEmail, Failed, OrchestratedEmailV2}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ComposerStream extends Logging {

  case class Input[T](topic: String, consumerSettings: ConsumerSettings[String, T])
  case class Output[T](topic: String, producer: KafkaProducer[String, T], retryConfig: Retry.RetryConfig)

  def build(input: Input[Option[OrchestratedEmailV2]],
            composedEmailEventOutput: => Output[ComposedEmail],
            failedEventOutput: => Output[Failed])(processEvent: OrchestratedEmailV2 => Either[Failed, ComposedEmail])(
      implicit scheduler: Scheduler): Source[Done, Control] = {

    def sendComposedEmail(composedEmail: ComposedEmail): Future[RecordMetadata] = {
      info(composedEmail.metadata.traceToken)(s"Sending ComposedEmail event. Metadata: ${composedEmail.metadata}")

      Retry.retryAsync(
        config = composedEmailEventOutput.retryConfig,
        onFailure = e =>
          warnE(composedEmail.metadata.traceToken)(
            s"Failed to send Kafka event to topic ${composedEmailEventOutput.topic}",
            e)
      ) { () =>
        composedEmailEventOutput.producer.send(
          new ProducerRecord(composedEmailEventOutput.topic, composedEmail.metadata.customerId, composedEmail))
      }
    }

    def sendFailed(failed: Failed): Future[RecordMetadata] = {
      info(failed.metadata.traceToken)(s"Sending Failed event: $failed")

      Retry.retryAsync(
        config = failedEventOutput.retryConfig,
        onFailure =
          e => warnE(failed.metadata.traceToken)(s"Failed to send Kafka event to topic ${failedEventOutput.topic}", e)
      ) { () =>
        failedEventOutput.producer.send(
          new ProducerRecord(failedEventOutput.topic, failed.metadata.customerId, failed))
      }
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
