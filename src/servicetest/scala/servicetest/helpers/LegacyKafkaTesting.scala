package servicetest.helpers

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.model.sms.OrchestratedSMSV2
import com.ovoenergy.comms.serialisation.Serialisation.avroSerializer
import org.apache.kafka.clients.consumer.{KafkaConsumer => ApacheKafkaConsumer}
import org.apache.kafka.common.serialization.StringSerializer
import org.scalatest.Assertions
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import servicetest.KafkaTopics
//Implicits
import com.sksamuel.avro4s._
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Serialisation._

trait LegacyKafkaTesting extends Eventually with ScalaFutures with Assertions with KafkaTopics {

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

}
