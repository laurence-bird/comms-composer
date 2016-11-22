package com.ovoenergy.comms

import java.time.OffsetDateTime
import java.util
import java.util.UUID

import cakesolutions.kafka._
import cakesolutions.kafka.KafkaProducer.{Conf => ProdConf}
import cakesolutions.kafka.KafkaConsumer.{Conf => ConsConf}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.kafka.Serialization
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

object DockerComposeTag extends Tag("DockerComposeTag")

class ServiceSpec extends FlatSpec with Matchers with OptionValues {

  "composer service" should "compose an email" taggedAs DockerComposeTag in {
    createKafkaTopics()
    uploadTemplateToS3()
    waitForKafkaConsumerToSettle()
    sendOrchestratedEmailEvent()
    verifyComposedEmailEvent()
    checkNoFailedEvent()
  }

  val config = ConfigFactory.load()
  val orchestratedEmailTopic = config.getString("kafka.topics.orchestrated.email")
  val composedEmailTopic = config.getString("kafka.topics.composed.email")
  val failedTopic = config.getString("kafka.topics.failed")
  val kafkaHosts = "localhost:29092"
  val zkHosts = "localhost:32181"
  val s3Endpoint = "http://localhost:4569"

  private def createKafkaTopics(): Unit = {
    import _root_.kafka.admin.AdminUtils
    import _root_.kafka.utils.ZkUtils
    val zkUtils = ZkUtils(zkHosts, 30000, 5000, isZkSecurityEnabled = false)

    // Note: the app itself creates the OrchestratedEmail topic by subscribing to it
    AdminUtils.createTopic(zkUtils, composedEmailTopic, 1, 1)
    AdminUtils.createTopic(zkUtils, failedTopic, 1, 1)
  }

  private def waitForKafkaConsumerToSettle(): Unit = {
    Thread.sleep(5000L) // :(
  }

  private def uploadTemplateToS3(): Unit = {
    // disable chunked encoding to work around https://github.com/jubos/fake-s3/issues/164
    val s3clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).disableChunkedEncoding().build()

    val s3: AmazonS3Client = new AmazonS3Client(new BasicAWSCredentials("service-test", "dummy"))
      .withRegion(Regions.fromName(config.getString("aws.region")))
    s3.setS3ClientOptions(s3clientOptions)
    s3.setEndpoint(s3Endpoint)

    s3.createBucket("ovo-comms-templates")

    // template
    s3.putObject("ovo-comms-templates",
                 "service/composer-service-test/0.1/email/subject.txt",
                 "SUBJECT {{profile.firstName}}")
    s3.putObject("ovo-comms-templates",
                 "service/composer-service-test/0.1/email/body.html",
                 "{{> header}} HTML BODY {{amount}}")
    s3.putObject("ovo-comms-templates",
                 "service/composer-service-test/0.1/email/body.txt",
                 "{{> header}} TEXT BODY {{amount}}")

    // fragments
    s3.putObject("ovo-comms-templates", "service/fragments/email/html/header.html", "HTML HEADER")
    s3.putObject("ovo-comms-templates", "service/fragments/email/text/header.txt", "TEXT HEADER")
  }

  private def sendOrchestratedEmailEvent(): Unit = {
    val orchestratedEmailSerializer = Serialization.avroSerializer[OrchestratedEmail]
    val producer = KafkaProducer(
      ProdConf(new StringSerializer, orchestratedEmailSerializer, bootstrapServers = kafkaHosts))
    val event = OrchestratedEmail(
      Metadata(
        OffsetDateTime.now().toString,
        UUID.randomUUID(),
        "customer123",
        "transaction123",
        "composer service test",
        "ServiceSpec",
        canary = true,
        None
      ),
      CommManifest(
        CommType.Service,
        "composer-service-test",
        "0.1"
      ),
      "chris.birchall@ovoenergy.com",
      CustomerProfile(
        "Chris",
        "Birchall"
      ),
      Map(
        "amount" -> "1.23"
      )
    )
    val future = producer.send(new ProducerRecord(orchestratedEmailTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def verifyComposedEmailEvent(): Unit = {
    val composedEmailDeserializer = Serialization.avroDeserializer[ComposedEmail]
    val consumer = KafkaConsumer(
      ConsConf(new StringDeserializer,
               composedEmailDeserializer,
               groupId = "test",
               bootstrapServers = "127.0.0.1:29092"))
    consumer.subscribe(util.Arrays.asList(composedEmailTopic))
    val records = consumer.poll(10000L)
    try {
      records.count() should be(1)
      val event = records.iterator().next().value().value

      event.subject should be("SUBJECT Chris")
      event.htmlBody should be("HTML HEADER HTML BODY 1.23")
      event.textBody should be(Some("TEXT HEADER TEXT BODY 1.23"))
      event.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
      event.metadata.transactionId should be("transaction123")
      event.metadata.customerId should be("customer123")
    } finally {
      consumer.commitSync()
    }
  }

  private def checkNoFailedEvent(): Unit = {
    val composedEmailDeserializer = Serialization.avroDeserializer[Failed]
    val consumer = KafkaConsumer(
      ConsConf(new StringDeserializer, composedEmailDeserializer, groupId = "test", bootstrapServers = kafkaHosts))
    consumer.subscribe(util.Arrays.asList(failedTopic))
    val records = consumer.poll(1000L)
    try {
      records.count() should be(0)
    } finally {
      consumer.commitSync()
    }
  }

}
