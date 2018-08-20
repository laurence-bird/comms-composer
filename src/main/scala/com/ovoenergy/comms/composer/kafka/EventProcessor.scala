package com.ovoenergy.comms.composer.kafka

import cats.effect.Effect
import cats.syntax.all._
import cats.Show
import com.ovoenergy.comms.composer.Main.Record
import com.ovoenergy.comms.composer.{ComposerError, Loggable, Logging}
import com.ovoenergy.comms.model.{FailedV3, Feedback, LoggableEvent}
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.clients.consumer.ConsumerRecord
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

  def apply[F[_], InEvent <: LoggableEvent, OutEvent <: LoggableEvent](
      outputProducer: => OutEvent => F[RecordMetadata],
      failedProducer: FailedV3 => F[RecordMetadata],
      feedbackProducer: Feedback => F[RecordMetadata],
      processEvent: InEvent => Either[ComposerError, OutEvent])(
      implicit buildFeedbackFrom: BuildFeedback[InEvent],
      F: Effect[F]): Record[InEvent] => F[Seq[RecordMetadata]] = { record: Record[InEvent] =>
    def sendFeedback(failedToComposeError: ComposerError, inEvent: InEvent): F[Seq[RecordMetadata]] = {

      val feedback = buildFeedbackFrom(inEvent, failedToComposeError)

      for {
        r1 <- failedProducer(feedback.legacy)
        r2 <- feedbackProducer(feedback.latest)
      } yield Seq(r1, r2)
    }

    def handleEvent(inEvent: InEvent) = {
      F.delay(info(inEvent)("Processing event")) >> {
        processEvent(inEvent) match {
          case Left(failed: ComposerError) =>
            sendFeedback(failed, inEvent)
          case Right(result) =>
            outputProducer(result).map(Seq(_))
        }
      }
    }

    for {
      _ <- F.delay(info(record)(s"Consumed ${record.show}"))
      res <- record.value match {
        case Some(inEvent) => handleEvent(inEvent)
        case None => {
          F.delay(warn(record)(s"Failed to deserialise kafka record ${record.show}"))
            .map(_ => Seq.empty[RecordMetadata])
        }
      }
    } yield res
  }
}
