package com.ovoenergy.comms.kafka

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.util

import com.ovoenergy.comms.{ComposedEmail, Failed, OrchestratedEmail}
import com.sksamuel.avro4s._
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag
import scala.util.Try

object Serialization {

  val log = LoggerFactory.getLogger(getClass)

  val orchestratedEmailDeserializer = avroDeserializer[OrchestratedEmail]
  val composedEmailSerializer = avroSerializer[ComposedEmail]
  val failedSerializer = avroSerializer[Failed]

  def avroDeserializer[T: SchemaFor: FromRecord: ClassTag]: Deserializer[Option[T]] =
    new Deserializer[Option[T]] {
      override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}
      override def close(): Unit = {}
      override def deserialize(topic: String, data: Array[Byte]): Option[T] = {
        val bais = new ByteArrayInputStream(data)
        val input = AvroJsonInputStream[T](bais)
        val result: Try[T] = input.singleEntity

        result.failed.foreach { e =>
          val className = implicitly[ClassTag[T]].runtimeClass.getSimpleName
          val eventContent = new String(data, StandardCharsets.UTF_8)
          log.warn(s"""Skippping event because it could not be deserialized to $className.
             |Event:
             |$eventContent
           """.stripMargin,
                   e)
        }

        result.toOption
      }
    }

  def avroSerializer[T: SchemaFor: ToRecord]: Serializer[T] =
    new Serializer[T] {
      override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}
      override def close(): Unit = {}
      override def serialize(topic: String, data: T): Array[Byte] = {
        val baos = new ByteArrayOutputStream()
        val output = AvroJsonOutputStream[T](baos)
        output.write(data)
        output.close()
        baos.toByteArray
      }
    }

}
