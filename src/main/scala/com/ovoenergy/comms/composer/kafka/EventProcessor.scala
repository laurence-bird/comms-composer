package com.ovoenergy.comms.composer.kafka

import cats.Show
import cats.syntax.all._
import cats.effect.{Async, Effect}
import com.ovoenergy.comms.composer.Main.{Record}
import com.ovoenergy.comms.composer.{ComposerError, Loggable, Logging}
import com.ovoenergy.comms.composer.sms.BuildFailedEventFrom
import com.ovoenergy.comms.model.{FailedV2, LoggableEvent}
import org.apache.kafka.clients.producer.RecordMetadata
import fs2._
import org.apache.kafka.clients.consumer.ConsumerRecord
import cats.syntax.functor._
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.higherKinds
object EventProcessor extends Logging {

  implicit def consumerRecordShow[K, V]: Show[ConsumerRecord[K, V]] = Show.show[ConsumerRecord[K, V]] { record =>
    s"kafkaTopic: ${record.topic()}, kafkaPartition: ${record.partition()}, kafkaOffset: ${record.offset()}"
  }

  implicit def consumerRecordLoggable[K, V]: Loggable[ConsumerRecord[K, V]] =
    Loggable.instance(
      record =>
        Map("kafkaTopic" -> record.topic(),
            "kafkaPartition" -> record.partition().toString,
            "kafkaOffset" -> record.offset().toString))

  def apply[F[_]: Effect, InEvent <: LoggableEvent, OutEvent <: LoggableEvent](
      outputProducer: => OutEvent => F[RecordMetadata],
      failedProducer: FailedV2 => F[RecordMetadata],
      processEvent: InEvent => Either[ComposerError, OutEvent])(
      implicit buildFailedEventFrom: BuildFailedEventFrom[InEvent]): Record[InEvent] => F[Unit] = {

    def sendOutput(event: OutEvent): F[RecordMetadata] = {
      outputProducer(event).onError {
        case NonFatal(e) =>
          Async[F].delay(
            warnWithException(event)(
              "Unable to produce event, however, processing has completed so offset will be committed regardless")(e))
      }
    }

    def sendFailed(failedToComposeError: ComposerError, inEvent: InEvent): F[RecordMetadata] = {

      val failed = buildFailedEventFrom(inEvent, failedToComposeError)

      failedProducer(failed).onError {
        case NonFatal(e) =>
          Async[F].delay(warnWithException(failed)(
            "Unable to produce Failed event, however, processing has completed so offset will be committed regardless")(
            e))
      }
    }

    def result: Record[InEvent] => F[Unit] = (record: Record[InEvent]) => {

      Async[F].delay(info(record)(s"Consumed ${record.show}")) >> (record.value match {
        case Some(inEvent) => {
          info(inEvent)("Processing event")
          val result: F[RecordMetadata] = processEvent(inEvent) match {
            case Left(failed: ComposerError) =>
              info(inEvent)(s"Processing failed, sending failed event")
              sendFailed(failed, inEvent)
            case Right(result) =>
              sendOutput(result)
          }
          import cats.implicits._
          result.map(_ => ())
        }
        case None => {
          Async[F].delay(warn(record)(s"Failed to deserialise kafka record ${record.show}"))
        }
      })
    }

    result
  }
}
