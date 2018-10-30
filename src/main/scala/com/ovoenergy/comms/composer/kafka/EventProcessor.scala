package com.ovoenergy.comms.composer.kafka

import cats.effect.Effect
import cats.syntax.all._
import cats.Show
import com.ovoenergy.comms.composer.Main.Record
import com.ovoenergy.comms.composer.{ComposerError, Loggable, Logging}
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.{CompositionError, FailedV3, Feedback, LoggableEvent}
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.clients.consumer.ConsumerRecord

import scala.language.higherKinds

object EventProcessor extends Logging {

  implicit def consumerRecordShow[K, V]: Show[ConsumerRecord[K, V]] =
    Show.show[ConsumerRecord[K, V]] { record =>
      s"kafkaTopic: ${record.topic()}, kafkaPartition: ${record.partition()}, kafkaOffset: ${record.offset()}"
    }

  val naughtyTemplateIds = Set(
    "82c00db7-16f0-4a19-984a-54b8789db436",
    "9173e5f3-53cd-48c4-9e51-784876210df6",
    "bb9b9cac-944e-4690-b6f3-1450c4e1b5fd",
    "1a10127e-82dc-426e-8fb5-8670c8a83ad7",
    "be69e7f4-160f-4cdc-846b-a108e94ddfe9",
    "f6986768-56c9-4ac2-b14e-19bae53c6be8",
    "6c264361-926d-407a-aff6-314b80b11e6a",
    "cdf527e1-b965-48a8-9d94-5d6014acb2f2",
    "621008be-5f0f-4c37-a2b7-5cb9d69bbe00",
    "403bfff5-f8ba-4ba1-babf-bf7604b60077",
    "0f8be8c3-e7c2-4f06-89ba-d998920cc15e",
    "21caf11f-9400-4dd7-8fb6-3a199381c7c9"
  )

  implicit def consumerRecordLoggable[K, V]: Loggable[ConsumerRecord[K, V]] =
    Loggable.instance(
      record =>
        Map(
          "kafkaTopic" -> record.topic(),
          "kafkaPartition" -> record.partition().toString,
          "kafkaOffset" -> record.offset().toString))

  def apply[F[_], InEvent <: LoggableEvent, OutEvent <: LoggableEvent](
      outputProducer: => OutEvent => F[RecordMetadata],
      failedProducer: FailedV3 => F[RecordMetadata],
      feedbackProducer: Feedback => F[RecordMetadata],
      processEvent: InEvent => Either[ComposerError, OutEvent])(
      implicit buildFeedbackFrom: BuildFeedback[InEvent],
      F: Effect[F]): Record[InEvent] => F[Seq[RecordMetadata]] = { record: Record[InEvent] =>
    def sendFeedback(
        failedToComposeError: ComposerError,
        inEvent: InEvent): F[Seq[RecordMetadata]] = {

      val feedback = buildFeedbackFrom(inEvent, failedToComposeError)

      for {
        r1 <- failedProducer(feedback.legacy)
        r2 <- feedbackProducer(feedback.latest)
      } yield Seq(r1, r2)
    }

    def handleEvent(inEvent: InEvent) = {
      inEvent match {
        case o: OrchestratedPrintV2 => {
          if(naughtyTemplateIds.contains(o.metadata.templateManifest.id))
            sendFeedback(ComposerError(s"Template with id: ${o.metadata.templateManifest.id} is disabled", CompositionError), inEvent)
          else composeComm
        }
        case _ => composeComm
      }
      def composeComm = {
        F.delay(info(inEvent)("Processing event")) >> {
          processEvent(inEvent) match {
            case Left(failed: ComposerError) =>
              sendFeedback(failed, inEvent)
            case Right(result) =>
              outputProducer(result).map(Seq(_))
          }
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
