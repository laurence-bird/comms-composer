package com.ovoenergy.comms.composer
package kafka

import model.ComposerError
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import fs2._
import cats.Show
import cats.implicits._
import cats.effect.{Sync, Effect}
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.sksamuel.avro4s.{SchemaFor, FromRecord, ToRecord}

// Do not refactor this import here because it provides the implicits for avro4s
import com.ovoenergy.comms.model._
import print.{ComposedPrintV2, OrchestratedPrintV2}
import sms.{ComposedSMS, OrchestratedSMSV3}
import email.{OrchestratedEmailV4, ComposedEmailV4}
import com.ovoenergy.kafka.common.event.EventMetadata
import com.ovoenergy.kafka.serialization._
import avro._
import avro4s._
import com.ovoenergy.fs2.kafka._
import com.ovoenergy.comms.model._
import types.OrchestratedEventV3
import org.apache.kafka.common.serialization._
import org.apache.kafka.clients._
import consumer.ConsumerRecord
import producer.{ProducerRecord, RecordMetadata}

import scala.concurrent.ExecutionContext
import KafkaStream._

object KafkaStream {

  implicit def consumerRecordLoggable[K, V]: Loggable[ConsumerRecord[K, V]] =
    (a: ConsumerRecord[K, V]) =>
      Map(
        "kafkaTopic" -> a.topic(),
        "kafkaPartition" -> a.partition().toString,
        "kafkaOffset" -> a.offset().toString
    )

  implicit def recordMetadataLoggable: Loggable[RecordMetadata] = (rm: RecordMetadata) => {
    Map(
      "kafkaTopic" -> rm.topic(),
      "kafkaPartition" -> rm.partition().toString,
      "kafkaOffset" -> rm.offset().toString
    )
  }

  implicit def recordMetadataShow: Show[RecordMetadata] =
    (t: RecordMetadata) => s"topic=${t.topic()} partition=${t.partition()} offset=${t.offset()}"

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

  case class KafkaConfig(
      topics: Topics,
      consumer: ConsumerSettings,
      producer: ProducerSettings,
      schemaRegistry: SchemaRegistryClientSettings)

  def producerRecord[K, V](topic: String, key: K, value: V): ProducerRecord[K, V] = {
    new ProducerRecord[K, V](topic, key, value)
  }

  def composerError(t: Throwable): ComposerError = t match {
    case ce: ComposerError => ce
    case _ => ComposerError(t.getMessage, CompositionError)
  }

  def apply[F[_]](config: KafkaConfig, hash: Hash[F], time: Time[F]): KafkaStream[F] =
    new KafkaStream[F](config, hash, time)

}

class KafkaStream[F[_]](config: KafkaConfig, hash: Hash[F], time: Time[F]) extends Logging {

  private val topics = config.topics

  // TODO implement a http4s one
  def schemaRegistryStream(implicit F: Sync[F]): Stream[F, SchemaRegistryClient] = {
    Stream
      .bracket(F.delay(JerseySchemaRegistryClient(config.schemaRegistry)))(
        client => Stream.emit(client),
        client => F.delay(client.close())
      )

  }

  def apply[A <: OrchestratedEventV3: SchemaFor: FromRecord, B: ToRecord](
      topicA: Topic[A],
      topicB: Topic[B])(
      process: A => F[B])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Unit] = {

    def failedEvent(a: A, e: ComposerError): F[FailedV3] = {
      (hash("composer-failed"), time.now).mapN { (eventIdSuffix, now) =>
        val failedMetadata = a.metadata.copy(
          createdAt = now.toInstant,
          eventId = a.metadata.eventId ++ "-" ++ eventIdSuffix
        )

        FailedV3(
          metadata = failedMetadata,
          internalMetadata = a.internalMetadata,
          reason = e.reason,
          errorCode = e.errorCode
        )
      }
    }

    def feedbackEvent(a: A, e: ComposerError): F[Feedback] = {
      (hash("composer-feedback"), time.now).mapN { (eventIdSuffix, now) =>
        val metadata = EventMetadata(
          eventId = a.metadata.eventId ++ "-" ++ eventIdSuffix,
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
          customer = customer,
          status = FeedbackOptions.Failed,
          channel = channel,
          description = s"Error: ${e.errorCode} ${e.reason}".some,
          email = none
        )
      }
    }

    val subscription = Subscription.topics(topicA.name)

    val keyDeserializer: Deserializer[String] = new StringDeserializer
    val keySerializer: Serializer[String] = new StringSerializer

    schemaRegistryStream.flatMap { schemaRegistryClient =>
      val valueDeserializer = avroBinarySchemaIdWithReaderSchemaDeserializer[A](
        schemaRegistryClient,
        isKey = false,
        includesFormatByte = true
      )

      // TODO this is completely stupid, I have to find a way to reuse the same producer
      val failedProducerS = producerStream[F](
        config.producer,
        keySerializer,
        avroBinarySchemaIdSerializer[FailedV3](
          schemaRegistryClient,
          isKey = false,
          includesFormatByte = true)
      )

      val feedbackProducerS = producerStream[F](
        config.producer,
        keySerializer,
        avroBinarySchemaIdSerializer[Feedback](
          schemaRegistryClient,
          isKey = false,
          includesFormatByte = true)
      )

      val bProducerS = producerStream[F](
        config.producer,
        keySerializer,
        avroBinarySchemaIdSerializer[B](
          schemaRegistryClient,
          isKey = false,
          includesFormatByte = true)
      )

      (bProducerS zip feedbackProducerS zip failedProducerS).flatMap {
        case ((bProducer, feedbackProducer), failedProducer) =>
          consumeProcessAndCommit[F](
            subscription,
            keyDeserializer,
            valueDeserializer,
            config.consumer
          ) { record =>
            val logRecord = F.delay(info(record)("Consumed Kafka message"))

            // TODO I believe this should be encapsulated in a logic that work on an algebra.
            logRecord *> process(record.value())
              .flatMap { b =>
                produceRecord[F](bProducer, producerRecord(topicB.name, record.key(), b))
                  .flatMap { metadata =>
                    F.delay(info(metadata)(s"Sent record to Kafka ${metadata.show}"))
                  }
              }
              .handleErrorWith { error =>
                val logFailure =
                  F.delay(warnWithException(record)("Error processing Kafka record")(error))

                val sendFailedEvent = failedEvent(record.value(), composerError(error)).flatMap(
                  event =>
                    produceRecord[F](
                      failedProducer,
                      producerRecord(topics.failed.name, record.key(), event))
                      .flatMap { metadata =>
                        F.delay(info(metadata)(s"Sent record to Kafka ${metadata.show}"))
                    })

                val sendFeedbackEvent =
                  feedbackEvent(record.value(), composerError(error)).flatMap { event =>
                    produceRecord[F](
                      feedbackProducer,
                      producerRecord(topics.feedback.name, record.key(), event))
                      .flatMap { metadata =>
                        F.delay(info(metadata)(s"Sent record to Kafka ${metadata.show}"))
                      }
                  }

                logFailure *> sendFailedEvent *> sendFeedbackEvent
              }
          }
      }

    }

  }

}
