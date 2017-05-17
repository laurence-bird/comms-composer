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
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import io.circe.generic.auto._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.{Failed => _, _}
import shapeless.Coproduct

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.JavaConverters._

object DockerComposeTag extends Tag("DockerComposeTag")

class ServiceTestIT extends FlatSpec with Matchers with OptionValues with BeforeAndAfterAll {

  behavior of "composer service"

  val config =
    ConfigFactory.load(ConfigParseOptions.defaults(), ConfigResolveOptions.defaults().setAllowUnresolved(true))
  val orchestratedEmailLegacyTopic = config.getString("kafka.topics.orchestrated.email.v2")
  val orchestratedSMSLegacyTopic = config.getString("kafka.topics.orchestrated.sms.v1")

  val orchestratedEmailTopic = config.getString("kafka.topics.orchestrated.email.v3")
  val orchestratedSMSTopic = config.getString("kafka.topics.orchestrated.sms.v2")

  val composedEmailTopic = config.getString("kafka.topics.composed.email.v2")
  val composedSMSTopic = config.getString("kafka.topics.composed.sms.v2")
  val failedTopic = config.getString("kafka.topics.failed.v2")
  val kafkaHosts = "localhost:29092"
  val zkHosts = "localhost:32181"
  val s3Endpoint = "http://localhost:4569"

  var orchestratedEmailLegacyProducer: KafkaProducer[String, OrchestratedEmailV2] = _
  var orchestratedSMSLegacyProducer: KafkaProducer[String, OrchestratedSMS] = _

  var orchestratedEmailProducer: KafkaProducer[String, OrchestratedEmailV3] = _
  var orchestratedSMSProducer: KafkaProducer[String, OrchestratedSMSV2] = _

  var composedEmailConsumer: KafkaConsumer[String, Option[ComposedEmailV2]] = _
  var composedSMSConsumer: KafkaConsumer[String, Option[ComposedSMSV2]] = _
  var failedConsumer: KafkaConsumer[String, Option[FailedV2]] = _

  override protected def beforeAll(): Unit = {
    uploadTemplateToS3()
    createKafkaTopics()
    Thread.sleep(3000L) // hopefully this will stop the random failures...
    createKafkaProducers()
    createKafkaConsumers()
    Thread.sleep(3000L) // yeah this one will definitely fix everything
  }

  override protected def afterAll(): Unit = {
    orchestratedEmailLegacyProducer.close()
    orchestratedSMSLegacyProducer.close()

    orchestratedEmailProducer.close()
    orchestratedSMSProducer.close()

    composedEmailConsumer.close()
    composedSMSConsumer.close()
    failedConsumer.close()
  }

  it should "compose an email from legacy orchestrated event" taggedAs DockerComposeTag in {
    sendOrchestratedEmailLegacyEvent(CommManifest(
                                       model.Service,
                                       "composer-service-test",
                                       "0.1"
                                     ),
                                     Map(
                                       "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
                                     ))
    verifyComposedEmailEvent()
    expectNoFailedEvent()
  }

  it should "compose an email" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEvent(CommManifest(
                                 model.Service,
                                 "composer-service-test",
                                 "0.1"
                               ),
                               Map(
                                 "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
                               ))
    verifyComposedEmailEvent()
    expectNoFailedEvent()
  }

  it should "send a failed event if some template data is missing" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEvent(
      CommManifest(
        model.Service,
        "composer-service-test",
        "0.1"
      ),
      Map.empty
    )
    expectNoComposedEmailEvent()
    expectNFailedEvents(1)
  }

  it should "send a failed event if the template does not exist" taggedAs DockerComposeTag in {
    sendOrchestratedEmailEvent(CommManifest(
                                 model.Service,
                                 "no-such-template",
                                 "9.9"
                               ),
                               Map.empty)
    expectNoComposedEmailEvent()
    expectNFailedEvents(1)
  }

  it should "compose an SMS from legacy orchestrated event" taggedAs DockerComposeTag in {
    sendOrchestratedSMSLegacyEvent(CommManifest(
                                     model.Service,
                                     "composer-service-test",
                                     "0.1"
                                   ),
                                   Map(
                                     "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
                                   ))
    verifyComposedSMSEvent()
    expectNoFailedEvent()
  }

  it should "compose an SMS" taggedAs DockerComposeTag in {
    sendOrchestratedSMSEvent(CommManifest(
                               model.Service,
                               "composer-service-test",
                               "0.1"
                             ),
                             Map(
                               "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
                             ))
    verifyComposedSMSEvent()
    expectNoFailedEvent()
  }

  private def createKafkaTopics(): Unit = {
    import _root_.kafka.admin.AdminUtils
    import _root_.kafka.utils.ZkUtils

    import scala.concurrent.duration._
    import scala.util.control.NonFatal

    val inputTopics =
      List(orchestratedEmailLegacyTopic, orchestratedSMSLegacyTopic, orchestratedEmailTopic, orchestratedSMSTopic)

    val zkUtils = ZkUtils(zkHosts, 30000, 5000, isZkSecurityEnabled = false)

    //Wait until kafka calls are not erroring and the service has created the topics it consumes from
    val timeout = 30.seconds.fromNow
    var notStarted = true
    while (timeout.hasTimeLeft && notStarted) {
      try {
        notStarted = !inputTopics.forall(AdminUtils.topicExists(zkUtils, _))
      } catch {
        case NonFatal(_) => Thread.sleep(100)
      }
    }
    if (notStarted) fail("Services did not start within 30 seconds")

    // Create the output topics that we want to consume from
    for (topic <- Seq(composedEmailTopic, composedSMSTopic, failedTopic)) {
      if (!AdminUtils.topicExists(zkUtils, topic)) {
        AdminUtils.createTopic(zkUtils, topic, 1, 1)
      }
    }
  }

  private def createKafkaProducers(): Unit = {
    orchestratedEmailLegacyProducer = KafkaProducer(
      ProdConf(new StringSerializer, avroSerializer[OrchestratedEmailV2], bootstrapServers = kafkaHosts))
    orchestratedEmailProducer = KafkaProducer(
      ProdConf(new StringSerializer, avroSerializer[OrchestratedEmailV3], bootstrapServers = kafkaHosts))
    orchestratedSMSLegacyProducer = KafkaProducer(
      ProdConf(new StringSerializer, avroSerializer[OrchestratedSMS], bootstrapServers = kafkaHosts))
    orchestratedSMSProducer = KafkaProducer(
      ProdConf(new StringSerializer, avroSerializer[OrchestratedSMSV2], bootstrapServers = kafkaHosts))
  }

  private def createKafkaConsumers(): Unit = {
    composedEmailConsumer = {
      val consumer = KafkaCons(
        ConsConf(new StringDeserializer,
                 avroDeserializer[ComposedEmailV2],
                 groupId = "test",
                 bootstrapServers = kafkaHosts,
                 maxPollRecords = 1))
      // DO NOT USE subscribe()! See https://github.com/dpkp/kafka-python/issues/690#issuecomment-220490765
      consumer.assign(List(new TopicPartition(composedEmailTopic, 0)).asJava)
      consumer
    }

    composedSMSConsumer = {
      val consumer = KafkaCons(
        ConsConf(new StringDeserializer,
                 avroDeserializer[ComposedSMSV2],
                 groupId = "test",
                 bootstrapServers = kafkaHosts,
                 maxPollRecords = 1))
      consumer.assign(List(new TopicPartition(composedSMSTopic, 0)).asJava)
      consumer
    }

    failedConsumer = {
      val consumer = KafkaCons(
        ConsConf(new StringDeserializer,
                 avroDeserializer[FailedV2],
                 groupId = "test",
                 bootstrapServers = kafkaHosts,
                 maxPollRecords = 1))
      consumer.assign(List(new TopicPartition(failedTopic, 0)).asJava)
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
    s3.putObject("ovo-comms-templates",
                 "service/composer-service-test/0.1/sms/body.txt",
                 "{{> header}} SMS BODY {{amount}}")

    // fragments
    s3.putObject("ovo-comms-templates", "service/fragments/email/html/header.html", "HTML HEADER")
    s3.putObject("ovo-comms-templates", "service/fragments/email/txt/header.txt", "TEXT HEADER")
    s3.putObject("ovo-comms-templates", "service/fragments/sms/txt/header.txt", "SMS HEADER")
  }

  def metadataLegacy(commManifest: CommManifest) = Metadata(
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

  def metadata(commManifest: CommManifest) = MetadataV2(
    OffsetDateTime.now().toInstant,
    UUID.randomUUID().toString,
    "transaction123",
    Customer("customer123"),
    commManifest,
    "composer service test",
    "ServiceSpec",
    canary = true,
    None,
    "SomeTriggerSource"
  )

  val internalMetadata = InternalMetadata(UUID.randomUUID().toString)
  val recipientEmailAddress = "chris.birchall@ovoenergy.com"
  val recipientPhoneNumber = "+447123456789"
  val profile = CustomerProfile(
    "Chris",
    "Birchall"
  )

  def orchestratedEmailLegacyEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]) =
    OrchestratedEmailV2(
      metadataLegacy(commManifest),
      internalMetadata,
      recipientEmailAddress,
      profile,
      templateData,
      expireAt = None
    )

  def orchestratedEmailEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]) =
    OrchestratedEmailV3(
      metadata(commManifest),
      internalMetadata,
      recipientEmailAddress,
      Some(profile),
      templateData,
      expireAt = None
    )

  def orchestratedSMSLegacyEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]) =
    OrchestratedSMS(
      metadataLegacy(commManifest),
      internalMetadata,
      recipientPhoneNumber,
      profile,
      templateData,
      expireAt = None
    )

  def orchestratedSMSEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]) =
    OrchestratedSMSV2(
      metadata(commManifest),
      internalMetadata,
      recipientPhoneNumber,
      Some(profile),
      templateData,
      expireAt = None
    )

  private def sendOrchestratedEmailLegacyEvent(commManifest: CommManifest,
                                               templateData: Map[String, TemplateData]): Unit = {
    val event = orchestratedEmailLegacyEvent(commManifest, templateData)
    val future = orchestratedEmailLegacyProducer.send(new ProducerRecord(orchestratedEmailLegacyTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def sendOrchestratedEmailEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]): Unit = {
    val event = orchestratedEmailEvent(commManifest, templateData)
    val future = orchestratedEmailProducer.send(new ProducerRecord(orchestratedEmailTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def sendOrchestratedSMSLegacyEvent(commManifest: CommManifest,
                                             templateData: Map[String, TemplateData]): Unit = {
    val event = orchestratedSMSLegacyEvent(commManifest, templateData)
    val future = orchestratedSMSLegacyProducer.send(new ProducerRecord(orchestratedSMSLegacyTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def sendOrchestratedSMSEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]): Unit = {
    val event = orchestratedSMSEvent(commManifest, templateData)
    val future = orchestratedSMSProducer.send(new ProducerRecord(orchestratedSMSTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def verifyComposedEmailEvent(): Unit = {
    @tailrec
    def poll(records: List[ComposedEmailV2], expNum: Int, deadLine: Deadline): List[ComposedEmailV2] = {
      if (records.size >= expNum || !deadLine.hasTimeLeft) records
      else
        poll(
          records ++ composedEmailConsumer.poll(1000L).iterator().asScala.toList.map(record => record.value().value),
          expNum,
          deadLine)
    }

    try {
      val records = poll(List(), 1, 30.seconds.fromNow)
      records.size should be(1)
      records.head.subject should be("SUBJECT Chris")
      records.head.htmlBody should be("HTML HEADER HTML BODY 1.23")
      records.head.textBody should be(Some("TEXT HEADER TEXT BODY 1.23"))
      records.head.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
      records.head.metadata.traceToken should be("transaction123")
    } finally {
      composedEmailConsumer.commitSync()
    }
  }

  private def verifyComposedSMSEvent(): Unit = {
    @tailrec
    def poll(records: List[ComposedSMSV2], expNum: Int, deadLine: Deadline): List[ComposedSMSV2] = {
      if (records.size >= expNum || !deadLine.hasTimeLeft) records
      else
        poll(records ++ composedSMSConsumer.poll(1000L).iterator().asScala.toList.map(record => record.value().value),
             expNum,
             deadLine)
    }

    try {
      val records = poll(List(), 1, 30.seconds.fromNow)
      records.size should be(1)
      records.head.textBody should be("SMS HEADER SMS BODY 1.23")
      records.head.metadata.traceToken should be("transaction123")
    } finally {
      composedSMSConsumer.commitSync()
    }
  }

  private def expectNoComposedEmailEvent(): Unit = expectNoEvent(composedEmailConsumer)

  private def expectNoComposedSMSEvent(): Unit = expectNoEvent(composedSMSConsumer)

  private def expectNoEvent(consumer: KafkaConsumer[_, _]): Unit = {
    val records = consumer.poll(10000L)
    try {
      records.count() should be(0)
    } finally {
      consumer.commitSync()
    }
  }

  private def expectNoFailedEvent(): Unit = expectNFailedEvents(0)

  private def expectNFailedEvents(n: Int): Unit = {
    @tailrec
    def poll(records: List[FailedV2], expNum: Int, deadLine: Deadline): List[FailedV2] = {
      if (records.size >= expNum || !deadLine.hasTimeLeft) records
      else
        poll(records ++ failedConsumer.poll(1000L).iterator().asScala.toList.map(record => record.value().value),
             expNum,
             deadLine)
    }

    try {
      val records = poll(List(), 1, 30.seconds.fromNow)
      records.size should be(n)
    } finally {
      failedConsumer.commitSync()
    }
  }

}
