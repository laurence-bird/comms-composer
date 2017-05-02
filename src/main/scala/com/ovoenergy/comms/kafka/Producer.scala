package com.ovoenergy.comms.kafka

import akka.actor.ActorSystem
import akka.stream.Materializer
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.Logging
import com.ovoenergy.comms.kafka.Retry._
import com.ovoenergy.comms.model.LoggableEvent
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.{Serializer, StringSerializer}

import scala.concurrent.{ExecutionContext, Future}

object Producer extends Logging {

  def apply[A <: LoggableEvent](hosts: String, topic: String, serialiser: Serializer[A], retryConfig: RetryConfig)(
    implicit actorSystem: ActorSystem, materializer: Materializer, ec: ExecutionContext): A => Future[RecordMetadata] = {

    implicit val scheduler = actorSystem.scheduler

    val producer = KafkaProducer(Conf(new StringSerializer, serialiser, hosts))

    (event: A) =>
    {
      debug(event)(s"Posting event to topic - $topic")
      retryAsync(
        config = retryConfig,
        onFailure = e => warnE(event)(s"Failed to send Kafka event to topic $topic", e)
      ) { () =>
        producer.send(new ProducerRecord[String, A](topic, event)).map { record =>
          info(event)(s"Posted event to topic - $topic (${record.offset()})")
          record
        }
      }
    }
  }

}
