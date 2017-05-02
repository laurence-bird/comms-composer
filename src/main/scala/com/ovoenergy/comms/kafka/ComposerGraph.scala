package com.ovoenergy.comms.kafka

import akka.Done
import akka.actor.Scheduler
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.scaladsl.Source
import com.ovoenergy.comms.Logging
import com.ovoenergy.comms.model._
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object ComposerGraph extends Logging {

  case class Input[T](topic: String, consumerSettings: ConsumerSettings[String, T])

  def build[InEvent <: LoggableEvent, OutEvent <: LoggableEvent](
      input: Input[Option[InEvent]],
      outputProducer: => OutEvent => Future[RecordMetadata],
      failedProducer: FailedV2 => Future[RecordMetadata])(processEvent: InEvent => Either[FailedV2, OutEvent])(
      implicit scheduler: Scheduler,
      ec: ExecutionContext): Source[Done, Control] = {

    def sendOutput(event: OutEvent): Future[_] = {
      outputProducer(event).recover {
        case NonFatal(e) =>
          warnE(event)(
            "Unable to produce event, however, processing has completed so offset will be committed regardless",
            e)
      }
    }

    def sendFailed(failed: FailedV2): Future[_] = {
      failedProducer(failed).recover {
        case NonFatal(e) =>
          warnE(failed)(
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
            info(inputEvent)(s"Processing event: $inputEvent")
            processEvent(inputEvent) match {
              case Left(failed) =>
                info(inputEvent)(s"Processing failed, sending failed event")
                sendFailed(failed)
              case Right(result) =>
                sendOutput(result)
            }
          case None =>
            Future.successful(())
        }
        future.flatMap { _ =>
          msg.committableOffset.commitScaladsl()
        }
      }
  }

}
