package com.ovoenergy.comms.composer.kafka

import java.util.concurrent._

import cats.{Id, Show}
import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import com.ovoenergy.comms.composer.Time
import com.ovoenergy.comms.composer.kafka.Kafka.Topic
import com.ovoenergy.comms.composer.model.ComposerError
import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.model.sms.{ComposedSMSV4, OrchestratedSMSV3}
import com.ovoenergy.comms.model.{
  CompositionError,
  Customer,
  Email,
  FailedV3,
  Feedback,
  FeedbackOptions,
  Print,
  SMS
}
import com.ovoenergy.kafka.common.event.EventMetadata
import com.ovoenergy.kafka.serialization._
import avro._
import avro4s._
import com.ovoenergy.comms.model._
import types.OrchestratedEventV3
import com.sksamuel.avro4s.{FromRecord, SchemaFor, ToRecord}
import fs2.Stream
import fs2.kafka._
import io.chrisdavenport.log4cats.StructuredLogger
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag

trait Kafka[F[_]] {
  def stream[In <: OrchestratedEventV3: SchemaFor: FromRecord: ClassTag, Out: ToRecord](
      in: Topic[In],
      out: Topic[Out],
      process: In => F[Out]): Stream[F, Unit]
}

object Kafka {

  case class Topic[A](name: String)

  case class Topics(
      orchestratedEmail: Topic[OrchestratedEmailV4],
      orchestratedSms: Topic[OrchestratedSMSV3],
      orchestratedPrint: Topic[OrchestratedPrintV2],
      composedEmail: Topic[ComposedEmailV4],
      composedSms: Topic[ComposedSMSV4],
      composedPrint: Topic[ComposedPrintV2],
      feedback: Topic[Feedback],
      failed: Topic[FailedV3])

  case class Config(
      sslProperties: Map[String, String],
      schemaRegistryClientSettings: SchemaRegistryClientSettings,
      bootstrapServer: String,
      groupId: String,
      topics: Topics)

  def apply[F[_]](config: Config, time: Time[F], log: StructuredLogger[F])(
      implicit F: ConcurrentEffect[F],
      cs: ContextShift[F],
      timer: Timer[F]): Kafka[F] = new Kafka[F] {

    override def stream[In <: OrchestratedEventV3: SchemaFor: FromRecord: ClassTag, Out: ToRecord](
        in: Topic[In],
        out: Topic[Out],
        process: In => F[Out]): Stream[F, Unit] = {

      def consumerRecordLoggable[K, V](a: ConsumerRecord[K, V]) =
        Map(
          "kafkaTopic" -> a.topic(),
          "kafkaPartition" -> a.partition().toString,
          "kafkaOffset" -> a.offset().toString
        ).toSeq

      def recordMetadataLoggable(co: CommittableOffset[F]) =
        Map(
          "kafkaTopic" -> co.topicPartition.topic(),
          "kafkaPartition" -> co.topicPartition.partition().toString,
          "kafkaOffset" -> co.offsetAndMetadata.offset().toString
        ).toSeq

      implicit def committableOffsetShow: Show[CommittableOffset[F]] =
        (t: CommittableOffset[F]) =>
          s"topic=${t.topicPartition.topic()} partition=${t.topicPartition
            .partition()} offset=${t.offsetAndMetadata.offset()}"

      def composerError(t: Throwable): ComposerError = t match {
        case ce: ComposerError => ce
        case _ =>
          ComposerError(Option(t.getMessage).getOrElse(t.getClass.getSimpleName), CompositionError)
      }

      def failedEvent(a: In, e: ComposerError): F[FailedV3] = {
        time.now.map { now =>
          val failedMetadata = a.metadata.copy(
            createdAt = now.toInstant,
            eventId = a.metadata.commId ++ "-failed"
          )

          FailedV3(
            metadata = failedMetadata,
            internalMetadata = a.internalMetadata,
            reason = e.reason,
            errorCode = e.errorCode
          )
        }
      }

      def feedbackEvent(a: In, e: ComposerError): F[Feedback] = {
        time.now.map { now =>
          val metadata = EventMetadata(
            eventId = a.metadata.commId ++ "-feedback-failed",
            traceToken = a.metadata.traceToken,
            createdAt = now.toInstant
          )

          val customer = a.metadata.deliverTo match {
            case customer: Customer => customer.some
            case _ => none[Customer]
          }

          val channel = a match {
            case _: OrchestratedEmailV4 => Email.some
            case _: OrchestratedSMSV3 => SMS.some
            case _: OrchestratedPrintV2 => Print.some
          }

          Feedback(
            metadata = metadata,
            commId = a.metadata.commId,
            commDescription = Some(a.metadata.friendlyDescription),
            customer = customer,
            status = FeedbackOptions.Failed,
            channel = channel,
            description = s"Error: ${e.errorCode} ${e.reason}".some,
            email = none,
            templateManifest = Some(a.metadata.templateManifest)
          )
        }
      }

      val consumerSettings = (executionContext: ExecutionContext) =>
        ConsumerSettings(
          keyDeserializer = new StringDeserializer,
          valueDeserializer = avroBinarySchemaIdDeserializer[In](
            config.schemaRegistryClientSettings,
            isKey = false,
            includesFormatByte = true),
          executionContext = executionContext
        ).withBootstrapServers(config.bootstrapServer)
          .withGroupId(config.groupId)
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withProperties(config.sslProperties)

      def producerSettings[A: ToRecord] =
        ProducerSettings(
          keySerializer = new StringSerializer,
          valueSerializer = avroBinarySchemaIdSerializer[A](
            config.schemaRegistryClientSettings,
            isKey = false,
            includesFormatByte = true)
        ).withBootstrapServers(config.bootstrapServer)
          .withAcks(Acks.All)
          .withProperties(config.sslProperties)

      val stream = for {
        executionContext <- consumerExecutionContextStream[F]
        processEc <- Stream
          .bracket(F.delay(new ForkJoinPool(16)))(ex => F.delay(ex.shutdown()))
          .map(ExecutionContext.fromExecutorService)
        consumer <- consumerStream[F]
          .using(consumerSettings(executionContext))
          .evalTap(_.subscribeTo(in.name))
        producer <- producerStream[F].using(producerSettings[Out])
        failedProducer <- producerStream[F].using(producerSettings[FailedV3])
        feedbackProducer <- producerStream[F].using(producerSettings[Feedback])
        _ <- consumer.stream
          .mapAsync(25) { (message: CommittableMessage[F, String, In]) =>
            val logConsumed =
              log.info(consumerRecordLoggable(message.record): _*)("Consumed Kafka message")

            val produce = process(message.record.value)
              .flatMap { result: Out =>
                val record = new ProducerRecord(out.name, message.record.key, result)
                val pm = ProducerMessage.single[Id].of(record, message.committableOffset)
                producer
                  .produceBatched(pm)
                  .flatTap(_.flatTap(res =>
                    log.info(
                      s"Sent record to Kafka topic ${res.records._2}, key ${message.record.key()}")))
                  .map(_.map(_.passthrough))
              }
              .handleErrorWith { err =>
                val logFailure = log.warn(consumerRecordLoggable(message.record): _*)(
                  s"Error processing Kafka record. $err")

                val createFailed = failedEvent(message.record.value, composerError(err))
                  .map(new ProducerRecord(config.topics.failed.name, message.record.key, _))
                  .map(ProducerMessage.single[Id].of(_, message.committableOffset))
                  .flatMap(failedProducer.produceBatched)

                val createFeedback = feedbackEvent(message.record.value, composerError(err))
                  .map(new ProducerRecord(config.topics.feedback.name, message.record.key, _))
                  .map(ProducerMessage.single[Id].of(_, message.committableOffset))
                  .flatMap(feedbackProducer.produceBatched)

                val createFailedandFeedback = (createFailed, createFeedback)
                  .mapN((a, b) => a)
                  .map(_.map(_.passthrough))

                logFailure *> createFailedandFeedback
              }

            logConsumed *> cs.evalOn(processEc)(produce)

          }
          .to(commitBatchWithinF(500, 15.seconds))
      } yield ()

      stream
    }
  }
}
