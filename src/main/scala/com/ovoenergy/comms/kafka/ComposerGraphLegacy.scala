package com.ovoenergy.comms.kafka

import akka.Done
import akka.actor.Scheduler
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.scaladsl.Source
import com.ovoenergy.comms.{LegacyLogging, Logging}
import com.ovoenergy.comms.model.{FailedV2, LoggableEvent}
import com.ovoenergy.comms.types.HasMetadata
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object ComposerGraphLegacy extends Logging {

  case class Input[T](topic: String, consumerSettings: ConsumerSettings[String, T])

  def build[InEvent <: HasMetadata, OutEvent <: LoggableEvent](
      input: Input[Option[InEvent]],
      outputProducer: => OutEvent => Future[RecordMetadata],
      failedProducer: FailedV2 => Future[RecordMetadata])(processEvent: InEvent => Either[FailedV2, OutEvent])(
      implicit scheduler: Scheduler,
      ec: ExecutionContext): Source[Done, Control] = {

    def sendOutput(event: OutEvent): Future[_] = {
      outputProducer(event).recover {
        case NonFatal(e) =>
          warnT(event)(
            "Unable to produce event, however, processing has completed so offset will be committed regardless",
            e)
      }
    }

    def sendFailed(failed: FailedV2): Future[_] = {
      failedProducer(failed).recover {
        case NonFatal(e) =>
          warnT(failed)(
            "Unable to produce Failed event, however, processing has completed so offset will be committed regardless",
            e)
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
            LegacyLogging.info(inputEvent)(s"Processing event: $inputEvent")
            processEvent(inputEvent) match {
              case Left(failed) =>
                LegacyLogging.info(inputEvent)(s"Processing failed, sending failed event")
                sendFailed(failed)
              case Right(result) =>
                sendOutput(result)
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
