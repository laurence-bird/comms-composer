package com.ovoenergy.comms.composer

import cats.Show
import cats.implicits._
import fs2.kafka._

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.RecordMetadata

import com.ovoenergy.comms.model.types._

object ShowInstances {

  implicit def committableOffsetShow[F[_]]: Show[CommittableOffset[F]] =
    (t: CommittableOffset[F]) =>
      s"topic=${t.topicPartition.topic()} partition=${t.topicPartition
        .partition()} offset=${t.offsetAndMetadata.offset()}"

  implicit def consumerRecordShow[K: Show, V: Show]: Show[ConsumerRecord[K, V]] =
    (t: ConsumerRecord[K, V]) =>
      s"topic=${t.topic} partition=${t.partition} offset=${t.offset} timestamp=${t.timestamp} key=${t.key.show} value=${t.value.show}"

  implicit def hasMetadataV3Show[A <: HasMetadataV3]: Show[A] =
    (t: A) =>
      s"eventId=${t.metadata.eventId} commId=${t.metadata.commId} traceToken=${t.metadata.traceToken}"

  implicit def recordMetadataShow: Show[RecordMetadata] =
    (t: RecordMetadata) =>
      s"topic=${t.topic} partition=${t.partition} offset=${t.offset} timestamp=${t.timestamp}"

}
