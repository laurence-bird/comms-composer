package com.ovoenergy.comms

import java.time.{LocalDateTime, OffsetDateTime}
import java.util
import java.util.UUID

import cakesolutions.kafka.{KafkaProducer, KafkaConsumer => KafkaCons}
import cakesolutions.kafka.KafkaProducer.{Conf => ProdConf}
import cakesolutions.kafka.KafkaConsumer.{Conf => ConsConf}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.kafka.Serialization
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

object DockerComposeTag extends Tag("DockerComposeTag")

class ServiceTestIT extends FlatSpec with Matchers with OptionValues with BeforeAndAfterAll {

  behavior of "composer service"

  val config =
    ConfigFactory.load(ConfigParseOptions.defaults(), ConfigResolveOptions.defaults().setAllowUnresolved(true))
  val orchestratedEmailTopic = config.getString("kafka.topics.orchestrated.email")
  val composedEmailTopic = config.getString("kafka.topics.composed.email")
  val failedTopic = config.getString("kafka.topics.failed")
  val kafkaHosts = "localhost:29092"
  val zkHosts = "localhost:32181"
  val s3Endpoint = "http://localhost:4569"

  var orchestratedEmailProducer: KafkaProducer[String, OrchestratedEmail] = _
  var composedEmailConsumer: KafkaConsumer[String, Option[ComposedEmail]] = _
  var failedConsumer: KafkaConsumer[String, Option[Failed]] = _

  override protected def beforeAll(): Unit = {
    createKafkaTopics()
    Thread.sleep(3000L) // hopefully this will stop the random failures...
    createKafkaProducer()
    createKafkaConsumers()
  }

  override protected def afterAll(): Unit = {
    orchestratedEmailProducer.close()
    composedEmailConsumer.close()
    failedConsumer.close()
  }

  it should "compose an email" taggedAs DockerComposeTag in {
    uploadTemplateToS3()
    sendOrchestratedEmailEvent(
      CommManifest(
        CommType.Service,
        "composer-service-test",
        "0.1"
      ))
    verifyComposedEmailEvent()
    expectNoFailedEvent()
  }

  it should "send a failed event if the template does not exist" taggedAs DockerComposeTag in {
    uploadTemplateToS3()
    sendOrchestratedEmailEvent(
      CommManifest(
        CommType.Service,
        "no-such-template",
        "9.9"
      ))
    expectNoComposedEmailEvent()
    expectNFailedEvents(1)
  }

  private def createKafkaTopics(): Unit = {
    import _root_.kafka.admin.AdminUtils
    import _root_.kafka.utils.ZkUtils
    import scala.concurrent.duration._
    import scala.util.control.NonFatal

    val zkUtils = ZkUtils(zkHosts, 30000, 5000, isZkSecurityEnabled = false)

    //Wait until kafka calls are not erroring and the service has created the OrchestratedEmail topic
    val timeout = 10.seconds.fromNow
    var notStarted = true
    while (timeout.hasTimeLeft && notStarted) {
      try {
        notStarted = !AdminUtils.topicExists(zkUtils, orchestratedEmailTopic)
      } catch {
        case NonFatal(ex) => Thread.sleep(100)
      }
    }
    if (notStarted) fail("Services did not start within 10 seconds")

    // Create the 2 output topics that we want to consume from
    AdminUtils.createTopic(zkUtils, composedEmailTopic, 1, 1)
    AdminUtils.createTopic(zkUtils, failedTopic, 1, 1)
  }

  private def createKafkaProducer(): Unit = {
    orchestratedEmailProducer = KafkaProducer(
      ProdConf(new StringSerializer, Serialization.avroSerializer[OrchestratedEmail], bootstrapServers = kafkaHosts))
  }

  private def createKafkaConsumers(): Unit = {
    composedEmailConsumer = {
      val consumer = KafkaCons(
        ConsConf(new StringDeserializer,
                 Serialization.avroDeserializer[ComposedEmail],
                 groupId = "test",
                 bootstrapServers = kafkaHosts,
                 maxPollRecords = 1))
      // DO NOT USE subscribe()! See https://github.com/dpkp/kafka-python/issues/690#issuecomment-220490765
      consumer.assign(util.Arrays.asList(new TopicPartition(composedEmailTopic, 0)))
      consumer
    }

    failedConsumer = {
      val consumer = KafkaCons(
        ConsConf(new StringDeserializer,
                 Serialization.avroDeserializer[Failed],
                 groupId = "test",
                 bootstrapServers = kafkaHosts,
                 maxPollRecords = 1))
      consumer.assign(util.Arrays.asList(new TopicPartition(failedTopic, 0)))
      consumer
    }
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

  private def sendOrchestratedEmailEvent(commManifest: CommManifest): Unit = {
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
      commManifest,
      "chris.birchall@ovoenergy.com",
      CustomerProfile(
        "Chris",
        "Birchall"
      ),
      Map(
        "amount" -> "1.23"
      )
    )
    val future = orchestratedEmailProducer.send(new ProducerRecord(orchestratedEmailTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def verifyComposedEmailEvent(): Unit = {
    val records = composedEmailConsumer.poll(3000L)
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
      composedEmailConsumer.commitSync()
    }
  }

  private def expectNoComposedEmailEvent(): Unit = {
    val records = composedEmailConsumer.poll(3000L)
    try {
      records.count() should be(0)
    } finally {
      composedEmailConsumer.commitSync()
    }
  }

  private def expectNoFailedEvent(): Unit = expectNFailedEvents(0)

  private def expectNFailedEvents(n: Int): Unit = {
    val records = failedConsumer.poll(3000L)
    try {
      records.count() should be(n)
    } finally {
      failedConsumer.commitSync()
    }
  }

}
