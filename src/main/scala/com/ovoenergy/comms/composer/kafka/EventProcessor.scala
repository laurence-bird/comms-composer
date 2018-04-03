package com.ovoenergy.comms.composer.kafka

import cats.Show
import cats.syntax.all._
import cats.effect.{Async, Effect, IO, Sync}
import com.ovoenergy.comms.composer.Main.{Record, warn}
import com.ovoenergy.comms.composer.{ComposerError, Loggable, Logging}
import com.ovoenergy.comms.composer.sms.BuildFailedEventFrom
import com.ovoenergy.comms.helpers.Topic
import com.ovoenergy.comms.model.{FailedV2, LoggableEvent}
import com.sksamuel.avro4s.{FromRecord, SchemaFor}
import org.apache.kafka.clients.producer.RecordMetadata
import fs2._
import org.apache.kafka.clients.consumer.ConsumerRecord

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext.Implicits.global

object EventProcessor extends Logging {

  implicit def consumerRecordShow[K, V]: Show[ConsumerRecord[K, V]] = Show.show[ConsumerRecord[K, V]] { record =>
    s"kafkaTopic: ${record.topic()}, kafkaPartition: ${record.partition()}, kafkaOffset: ${record.offset()}"
  }

  def apply[F[_]: Effect, InEvent <: LoggableEvent, OutEvent <: LoggableEvent](
      topic: Topic[InEvent],
      outputProducer: => OutEvent => Future[RecordMetadata],
      failedProducer: FailedV2 => Future[RecordMetadata],
      processEvent: InEvent => Either[ComposerError, OutEvent])(
      implicit buildFailedEventFrom: BuildFailedEventFrom[InEvent]): Record[InEvent] => F[Unit] = {

    def sendOutput(event: OutEvent): Future[_] = {
      outputProducer(event).recover {
        case NonFatal(e) =>
          warnT(event)(
            "Unable to produce event, however, processing has completed so offset will be committed regardless",
            e)
      }
    }

    def sendFailed(failedToComposeError: ComposerError, inEvent: InEvent): Future[_] = {

      val failed = buildFailedEventFrom(inEvent, failedToComposeError)

      failedProducer(failed).recover {
        case NonFatal(e) =>
          warnT(failed)(
            "Unable to produce Failed event, however, processing has completed so offset will be committed regardless",
            e)
      }
    }

    def result[F[_]: Effect]: Record[InEvent] => F[Unit] = (record: Record[InEvent]) => {

      record.value match {
        case Some(inEvent) => {
          processEvent(inEvent) match {
            case Left(failed) =>
              info(inEvent)(s"Processing failed, sending failed event")
              sendFailed(failed, inEvent)
              Async[F].pure(())
            case Right(result) =>
              sendOutput(result)
              Async[F].pure(())
          }
        }
        case None => {
          log.warn(s"Failed to deserialise kafka record ${record.show}")
          Async[F].pure(())
        }
      }
    }

    result[F]
  }
}
