package servicetest.helpers

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import cakesolutions.kafka.{KafkaConsumer, KafkaProducer}
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.model.sms.OrchestratedSMSV2
import com.ovoenergy.comms.serialisation.Serialisation.avroSerializer
import org.apache.kafka.clients.consumer.{KafkaConsumer => ApacheKafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.Assertions
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import servicetest.KafkaTopics

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
//Implicits
import com.sksamuel.avro4s._
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Serialisation._

trait KafkaTesting extends Eventually with ScalaFutures with Assertions with KafkaTopics {

  implicit val pConfig: PatienceConfig = PatienceConfig(Span(10, Seconds))

  val legacyKafkaHosts = "localhost:29092"
  val legacyZookeeperHosts = "localhost:32181"

  lazy val legacyOrchestratedSMSProducer = legacyProducer[OrchestratedSMSV2]
  lazy val legacyOrchestratedEmailProducer = legacyProducer[OrchestratedEmailV3]

  def legacyProducer[T: SchemaFor: ToRecord]: KafkaProducer[String, T] = {
    val producer = KafkaProducer(
      KafkaProducerConf(new StringSerializer, avroSerializer[T], legacyKafkaHosts)
    )
    producer
  }

//  def stabiliseTopic[E](topic: String, producer: KafkaProducer[String, E], event: E, hosts: String)(
//      implicit ec: ExecutionContext) = {
//
//    val consumer = KafkaConsumer(
//      KafkaConsumerConf(new StringDeserializer, new StringDeserializer, hosts, consumerGroup))
//    consumer.assign(Seq(new TopicPartition(topic, 0)).asJava)
//    consumer.poll(1000)
//
//    sendEvent(producer, topic, event)
//
//    val deadline = 60.seconds.fromNow
//    var eventHandled = false
//    while (!eventHandled && deadline.hasTimeLeft()) {
//      if (consumer.poll(1000).count() > 0) {
//        consumer.commitSync()
//        eventHandled = true
//      }
//    }
//    if (!eventHandled) throw new IllegalStateException(s"Unable to stabilize topic $topic with test event")
//    else println(s"Stabilized topic $topic")
//  }

}
