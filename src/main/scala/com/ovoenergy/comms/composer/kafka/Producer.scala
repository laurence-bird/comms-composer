package com.ovoenergy.comms.composer.kafka

import akka.actor.ActorSystem
import akka.stream.Materializer
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.composer.Logging
import com.ovoenergy.comms.composer.kafka.Retry._
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.comms.serialisation.Serialisation
import com.ovoenergy.kafka.serialization.avro.SchemaRegistryClientSettings
import com.sksamuel.avro4s.{SchemaFor, ToRecord}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.{ExecutionContext, Future}

object Producer extends Logging {

  def apply[A <: LoggableEvent: ToRecord: SchemaFor](hosts: String,
                                                     topic: String,
                                                     schemaRegistryClientSettings: SchemaRegistryClientSettings,
                                                     retryConfig: RetryConfig)(
      implicit actorSystem: ActorSystem,
      materializer: Materializer,
      ec: ExecutionContext): A => Future[RecordMetadata] = {

    implicit val scheduler = actorSystem.scheduler

    val serialiser = Serialisation.avroBinarySchemaRegistrySerializer[A](schemaRegistryClientSettings, topic)
    val producer = KafkaProducer(Conf(new StringSerializer, serialiser, hosts))

    (event: A) =>
      {
        debug(event)(s"Posting event to topic - $topic: $event")
        retryAsync(
          config = retryConfig,
          onFailure = e => warnT(event)(s"Failed to send Kafka event to topic $topic", e)
        ) { () =>
          producer.send(new ProducerRecord[String, A](topic, event)).map { record =>
            infoE(event)(s"Posted event to topic - $topic (${record.offset()})")
            record
          }
        }
      }
  }

}
