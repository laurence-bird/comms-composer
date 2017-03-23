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
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.types.HasMetadata
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ComposerGraph extends Logging {

  case class Input[T](topic: String, consumerSettings: ConsumerSettings[String, T])
  case class Output[T](topic: String, producer: KafkaProducer[String, T], retryConfig: Retry.RetryConfig)

  def build[InEvent <: HasMetadata, OutEvent <: HasMetadata](input: Input[Option[InEvent]],
                                                             output: => Output[OutEvent],
                                                             failedEventOutput: => Output[Failed])(
      processEvent: InEvent => Either[Failed, OutEvent])(implicit scheduler: Scheduler): Source[Done, Control] = {

    def sendOutputEvent(event: OutEvent): Future[RecordMetadata] = {
      info(event)(s"Sending ComposedEmail event")

      Retry.retryAsync(
        config = output.retryConfig,
        onFailure = e => warnE(event)(s"Failed to send Kafka event to topic ${output.topic}", e)
      ) { () =>
        output.producer.send(new ProducerRecord(output.topic, event.metadata.customerId, event))
      }
    }

    def sendFailed(failed: Failed): Future[RecordMetadata] = {
      info(failed)(s"Sending Failed event: $failed")

      Retry.retryAsync(
        config = failedEventOutput.retryConfig,
        onFailure = e => warnE(failed)(s"Failed to send Kafka event to topic ${failedEventOutput.topic}", e)
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
          case Some(inputEvent) =>
            info(inputEvent)(s"Processing event: $inputEvent")
            processEvent(inputEvent) match {
              case Left(failed) => sendFailed(failed)
              case Right(result) => sendOutputEvent(result)
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
