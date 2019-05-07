package com.ovoenergy.comms.composer
package kafka

import java.util.concurrent._
import java.time.{Duration => JtDuration}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.{Id, Show}
import cats.data.NonEmptyList
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

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
import com.ovoenergy.comms.deduplication.ProcessingStore
import com.ovoenergy.comms.logging._

import avro._
import avro4s._
import com.ovoenergy.comms.model._
import types.{OrchestratedEventV3, ComposedEventV3}
import com.sksamuel.avro4s.{FromRecord, SchemaFor, ToRecord}
import fs2.Stream
import fs2.kafka._
import io.chrisdavenport.log4cats.StructuredLogger
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import metrics.Reporter

import Loggable._
import ShowInstances._

trait Kafka[F[_]] {
  def stream[
      In <: OrchestratedEventV3: SchemaFor: FromRecord: Loggable: Show,
      Out <: ComposedEventV3: ToRecord: Loggable](
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

  def apply[F[_]: Reporter](
      config: Config,
      time: Time[F],
      deduplication: ProcessingStore[F, String],
      log: StructuredLogger[F])(
      implicit F: ConcurrentEffect[F],
      cs: ContextShift[F],
      timer: Timer[F]): Kafka[F] = new Kafka[F] {

    override def stream[
        In <: OrchestratedEventV3: SchemaFor: FromRecord: Loggable: Show,
        Out <: ComposedEventV3: ToRecord: Loggable](
        in: Topic[In],
        out: Topic[Out],
        process: In => F[Out]): Stream[F, Unit] = {

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
        _ <- consumer.partitionedStream.parJoinUnbounded
          .mapAsync(128) { (message: CommittableMessage[F, String, In]) =>
            val orchestrated: OrchestratedEventV3 = message.record.value

            val channel = orchestrated match {
              case _: OrchestratedEmailV4 => Email.some
              case _: OrchestratedSMSV3 => SMS.some
              case _: OrchestratedPrintV2 => Print.some
            }

            val metricsTags = Map(
              "template-id" -> orchestrated.metadata.templateManifest.id,
              "template-version" -> orchestrated.metadata.templateManifest.version,
              "channel" -> channel.toString.toLowerCase
            )

            val logConsumed =
              log.info(logContext(message.record): _*)(
                s"Consumed Kafka message ${message.record.show}")

            val produce = {

              val recordConsumptionLatency: F[Unit] =
                Reporter[F]
                  .timer(
                    "consumption-latency",
                    metricsTags
                  )
                  .flatMap { timer =>
                    time.now
                      .map(now => JtDuration.between(orchestrated.metadata.createdAt, now))
                      .flatMap(timer.record)
                  }

              def recordProcessingTime[A](fa: F[A]): F[A] =
                Reporter[F]
                  .timer(
                    "processing-time",
                    metricsTags
                  )
                  .flatMap { timer =>
                    time.now.bracket(_ => fa) { start =>
                      time.now.map(now => JtDuration.between(start, now)).flatMap(timer.record)
                    }
                  }

              def recordTimeToNextmessage[A](nextMessage: Out): F[Unit] = {

                val duration = JtDuration.between(
                  orchestrated.metadata.createdAt,
                  nextMessage.metadata.createdAt
                )

                Reporter[F]
                  .timer(
                    "time-to-next-message",
                    metricsTags
                  )
                  .flatMap(_.record(duration))
              }

              val handleMessage: F[F[fs2.kafka.CommittableOffset[F]]] = {
                recordConsumptionLatency *> recordProcessingTime(process(message.record.value))
                  .flatMap { result: Out =>
                    val record = ProducerRecord(out.name, message.record.key, result)
                    val pm = ProducerMessage.one(record, message.committableOffset)

                    recordTimeToNextmessage(result) *> producer
                      .produce(pm)
                      .flatTap(_.flatTap(res =>
                        log.info(logContext(res.records._1, res.records._2): _*)(
                          s"Sent record to Kafka topic ${res.records._2.show}"
                      )))
                      .map(_.map(_.passthrough))
                  }
              }

              deduplication
                .protect(
                  message.record.value.metadata.eventId,
                  handleMessage,
                  log.warn(logContext(message.record): _*)(
                    s"Skip duplicate event: ${message.record.value.metadata.eventId}") *> message.committableOffset
                    .pure[F]
                    .pure[F]
                )
                .handleErrorWith { err =>
                  val logFailure =
                    log.warn(logContext(message.record, err): _*)(
                      s"Error processing Kafka record. $err") *> log
                      .debug("Error processing Kafka record:" ++ err.getStackTrace.mkString("\n"))

                  val createFailed = failedEvent(message.record.value, composerError(err))
                    .map(ProducerRecord(config.topics.failed.name, message.record.key, _))
                    .map(ProducerMessage.one(_, message.committableOffset))
                    .flatMap(failedProducer.produce)
                    .flatTap(_.flatTap(res =>
                      log.info(logContext(res.records._1, res.records._2): _*)(
                        s"Sent record to Kafka topic ${res.records._2.show}"
                    )))

                  val createFeedback = feedbackEvent(message.record.value, composerError(err))
                    .map(ProducerRecord(config.topics.feedback.name, message.record.key, _))
                    .map(ProducerMessage.one(_, message.committableOffset))
                    .flatMap(feedbackProducer.produce)
                    .flatTap(_.flatTap(res =>
                      log.info(logContext(res.records._1, res.records._2): _*)(
                        s"Sent record to Kafka topic ${res.records._2.show}"
                    )))

                  val createFailedandFeedback = (createFailed, createFeedback)
                    .mapN((a, b) => a)
                    .map(_.map(_.passthrough))

                  logFailure *> createFailedandFeedback
                }
            }

            logConsumed *> cs.evalOn(processEc)(produce)

          }
          .through(commitBatchWithinF(500, 15.seconds))
      } yield ()

      stream
    }
  }
}
