package com.ovoenergy.comms

import java.time.OffsetDateTime
import java.util
import java.util.UUID

import cakesolutions.kafka.KafkaConsumer.{Conf => ConsConf}
import cakesolutions.kafka.KafkaProducer.{Conf => ProdConf}
import cakesolutions.kafka.{KafkaProducer, KafkaConsumer => KafkaCons}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.comms.serialisation.Decoders._
import io.circe.generic.auto._
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.{Failed => _, _}
import shapeless.Coproduct

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object DockerComposeTag extends Tag("DockerComposeTag")

class ServiceTestIT extends FlatSpec with Matchers with OptionValues with BeforeAndAfterAll {

  behavior of "composer service"

  val config =
    ConfigFactory.load(ConfigParseOptions.defaults(), ConfigResolveOptions.defaults().setAllowUnresolved(true))
  val orchestratedEmailTopic = config.getString("kafka.topics.orchestrated.email.v2")
  val orchestratedEmailV1Topic = config.getString("kafka.topics.orchestrated.email.v1")
  val composedEmailTopic = config.getString("kafka.topics.composed.email")
  val failedTopic = config.getString("kafka.topics.failed")
  val kafkaHosts = "localhost:29092"
  val zkHosts = "localhost:32181"
  val s3Endpoint = "http://localhost:4569"

  var orchestratedEmailProducer: KafkaProducer[String, OrchestratedEmailV2] = _
  var orchestratedEmailV1Producer: KafkaProducer[String, OrchestratedEmail] = _
  var composedEmailConsumer: KafkaConsumer[String, Option[ComposedEmail]] = _
  var failedConsumer: KafkaConsumer[String, Option[Failed]] = _

  override protected def beforeAll(): Unit = {
    uploadTemplateToS3()
    createKafkaTopics()
    Thread.sleep(3000L) // hopefully this will stop the random failures...
    createKafkaProducers()
    createKafkaConsumers()
    Thread.sleep(3000L) // yeah this one will definitely fix everything
  }

  override protected def afterAll(): Unit = {
    orchestratedEmailProducer.close()
    orchestratedEmailV1Producer.close()
    composedEmailConsumer.close()
    failedConsumer.close()
  }

  it should "compose an email" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEventV2(CommManifest(
                                   CommType.Service,
                                   "composer-service-test",
                                   "0.1"
                                 ),
                                 Map(
                                   "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
                                 ))
    verifyComposedEmailEvent()
    expectNoFailedEvent()
  }

  it should "also consume using the old orchestrated events" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEventV1(CommManifest(
                                   CommType.Service,
                                   "composer-service-test",
                                   "0.1"
                                 ),
                                 Map(
                                   "amount" -> "1.23"
                                 ))
    verifyComposedEmailEvent()
    expectNoFailedEvent()
  }

  it should "send a failed event if some template data is missing" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEventV2(
      CommManifest(
        CommType.Service,
        "composer-service-test",
        "0.1"
      ),
      Map.empty
    )
    expectNoComposedEmailEvent()
    expectNFailedEvents(1)
  }

  it should "send a failed event if the template does not exist" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEventV2(CommManifest(
                                   CommType.Service,
                                   "no-such-template",
                                   "9.9"
                                 ),
                                 Map.empty)
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

  private def createKafkaProducers(): Unit = {
    orchestratedEmailProducer = KafkaProducer(
      ProdConf(new StringSerializer, avroSerializer[OrchestratedEmailV2], bootstrapServers = kafkaHosts))
    orchestratedEmailV1Producer = KafkaProducer(
      ProdConf(new StringSerializer, avroSerializer[OrchestratedEmail], bootstrapServers = kafkaHosts))
  }

  private def createKafkaConsumers(): Unit = {
    composedEmailConsumer = {
      val consumer = KafkaCons(
        ConsConf(new StringDeserializer,
                 avroDeserializer[ComposedEmail],
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
                 avroDeserializer[Failed],
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

  def metadata(commManifest: CommManifest) = Metadata(
    OffsetDateTime.now().toString,
    UUID.randomUUID().toString,
    "customer123",
    "transaction123",
    commManifest,
    "composer service test",
    "ServiceSpec",
    canary = true,
    None,
    "SomeTriggerSource"
  )
  val internalMetadata = InternalMetadata(UUID.randomUUID().toString)
  val recepientEmailAddress = "chris.birchall@ovoenergy.com"
  val profile = CustomerProfile(
    "Chris",
    "Birchall"
  )
  def v1(commManifest: CommManifest, templateData: Map[String, String]) =
    OrchestratedEmail(
      metadata(commManifest),
      internalMetadata,
      recepientEmailAddress,
      profile,
      templateData
    )
  def v2(commManifest: CommManifest, templateData: Map[String, TemplateData]) =
    OrchestratedEmailV2(
      metadata(commManifest),
      internalMetadata,
      recepientEmailAddress,
      profile,
      templateData
    )

  private def sendOrchestratedEmailEventV1(commManifest: CommManifest, templateData: Map[String, String]): Unit = {
    val event = v1(commManifest, templateData)
    val future = orchestratedEmailV1Producer.send(new ProducerRecord(orchestratedEmailV1Topic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka V1 message: $result")
  }

  private def sendOrchestratedEmailEventV2(commManifest: CommManifest, templateData: Map[String, TemplateData]): Unit = {
    val event = v2(commManifest, templateData)
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
      event.metadata.traceToken should be("transaction123")
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
