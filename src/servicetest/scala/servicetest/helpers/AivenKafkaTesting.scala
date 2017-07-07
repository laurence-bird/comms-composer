package servicetest.helpers

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import cakesolutions.kafka.{KafkaConsumer, KafkaProducer}
import com.ovoenergy.comms.model.FailedV2
import com.ovoenergy.comms.model.email.{ComposedEmailV2, OrchestratedEmailV3}
import com.ovoenergy.comms.model.sms.{ComposedSMSV2, OrchestratedSMSV2}
import com.ovoenergy.kafka.serialization.avro.{Authentication, SchemaRegistryClientSettings}
import com.ovoenergy.kafka.serialization.avro4s.{avroBinarySchemaIdDeserializer, avroBinarySchemaIdSerializer}
import com.sksamuel.avro4s.{FromRecord, SchemaFor, ToRecord}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.duration._
import servicetest.KafkaTopics
import org.apache.kafka.clients.consumer.{KafkaConsumer => ApacheKafkaConsumer}
import org.scalatest.time.{Seconds, Span}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Random
// Implicits
import com.sksamuel.avro4s._
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Serialisation._

trait AivenKafkaTesting extends KafkaTopics with Eventually {

  val schemaRegistrySettings = SchemaRegistryClientSettings("http://localhost:8081", Authentication.None, 100, 1)
  val aivenKafkaHosts = "localhost:29093"
  val aivenZookeeperHosts = "localhost:32182"
  val consumerGroup = Random.nextString(10)

  lazy val composedEmailConsumer = aivenConsumer[ComposedEmailV2](composedEmailTopic)
  lazy val composedSMSConsumer = aivenConsumer[ComposedSMSV2](composedSMSTopic)
  lazy val failedConsumer = aivenConsumer[FailedV2](failedTopic)
  lazy val orchestratedSMSProducer = aivenProducer[OrchestratedSMSV2]
  lazy val orchestratedEmailProducer = aivenProducer[OrchestratedEmailV3]

  def aivenProducer[T: SchemaFor: ToRecord]: KafkaProducer[String, T] = {
    val producer = KafkaProducer(
      KafkaProducerConf(new StringSerializer,
                        avroBinarySchemaIdSerializer[T](schemaRegistrySettings, isKey = false),
                        aivenKafkaHosts)
    )
    producer
  }

  def aivenConsumer[T: SchemaFor: FromRecord: ClassTag](topic: String) = {
    val consumer = KafkaConsumer(
      KafkaConsumerConf(new StringDeserializer,
                        avroBinarySchemaIdDeserializer[T](schemaRegistrySettings, isKey = false),
                        aivenKafkaHosts,
                        consumerGroup)
    )
    consumer.assign(Seq(new TopicPartition(topic, 0)).asJava)
    consumer
  }

  def initialiseConsumers() = {
    composedEmailConsumer.poll(250L)
    failedConsumer.poll(250L)
    composedSMSConsumer.poll(250L)
  }

  def sendEventAndTest[E](producer: KafkaProducer[String, E], topic: String, event: E)(f: => Unit): Unit = {
    sendEvent(producer, topic, event)
    eventually(PatienceConfiguration.Timeout(Span(15, Seconds))) {
      f
    }
  }

  def sendEvent[E](producer: KafkaProducer[String, E], topic: String, event: E): Unit = {
    val future = producer.send(new ProducerRecord[String, E](topic, event))
    Await.result(future, 5.seconds)
  }

  def pollForEvents[E](pollTime: FiniteDuration = 30.second,
                       noOfEventsExpected: Int,
                       consumer: ApacheKafkaConsumer[String, E],
                       topic: String): Seq[E] = {
    @tailrec
    def poll(deadline: Deadline, events: Seq[E]): Seq[E] = {
      if (deadline.hasTimeLeft) {
        val polledEvents: Seq[E] = consumer
          .poll(250)
          .records(topic)
          .asScala
          .toList
          .map(_.value())
        val eventsSoFar: Seq[E] = events ++ polledEvents
        eventsSoFar.length match {
          case n if n == noOfEventsExpected => eventsSoFar
          case exceeded if exceeded > noOfEventsExpected =>
            throw new Exception(s"Consumed more than $noOfEventsExpected events from $topic")
          case _ => poll(deadline, eventsSoFar)
        }
      } else
        throw new Exception("Events didn't appear within the timelimit")
    }
    poll(pollTime.fromNow, Nil)
  }
}
