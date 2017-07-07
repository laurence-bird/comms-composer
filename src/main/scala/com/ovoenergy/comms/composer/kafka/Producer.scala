package com.ovoenergy.comms.composer.kafka

import akka.actor.ActorSystem
import akka.stream.Materializer
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.akka.streams.Factory.SSLConfig
import com.ovoenergy.comms.composer.Logging
import com.ovoenergy.comms.composer.kafka.Retry._
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.comms.serialisation.Serialisation
import com.ovoenergy.kafka.serialization.avro.SchemaRegistryClientSettings
import com.sksamuel.avro4s.{SchemaFor, ToRecord}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.{ExecutionContext, Future}

object Producer extends Logging {

  def apply[A <: LoggableEvent: ToRecord: SchemaFor](hosts: String,
                                                     topic: String,
                                                     sslConfig: Option[SSLConfig],
                                                     schemaRegistryClientSettings: SchemaRegistryClientSettings,
                                                     retryConfig: RetryConfig)(
      implicit actorSystem: ActorSystem,
      materializer: Materializer,
      ec: ExecutionContext): A => Future[RecordMetadata] = {
    implicit val scheduler = actorSystem.scheduler

    val serialiser = Serialisation.avroBinarySchemaRegistrySerializer[A](schemaRegistryClientSettings, topic)
    val producerConf = {
      val conf = Conf(new StringSerializer, serialiser, hosts)
      sslConfig.fold(conf) { ssl =>
        conf
          .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
          .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ssl.keystoreLocation.toAbsolutePath.toString)
          .withProperty(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, ssl.keystoreType.toString)
          .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, ssl.keystorePassword)
          .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, ssl.keyPassword)
          .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ssl.truststoreLocation.toString)
          .withProperty(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, ssl.truststoreType.toString)
          .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, ssl.truststorePassword)
      }
    }
    val producer = KafkaProducer(producerConf)

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
