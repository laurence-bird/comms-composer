package servicetest

import java.time.OffsetDateTime
import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.scalatest.{Failed => _, _}
import servicetest.helpers.AivenKafkaTesting
import shapeless.Coproduct
import scala.concurrent.Await
import scala.concurrent.duration._

class AivenServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest
    with AivenKafkaTesting {

  behavior of "Composer service consuming from Aiven"

  val config: Config =
    ConfigFactory.load(ConfigParseOptions.defaults(), ConfigResolveOptions.defaults().setAllowUnresolved(true))

  val kafkaHosts = "localhost:29092"
  val zkHosts = "localhost:32181"
  val s3Endpoint = "http://localhost:4569"

  override def beforeAll(): Unit = {
    super.beforeAll()
    initialiseConsumers()
    uploadTemplateToS3()
  }

  override def afterAll() = {
    closeAivenKafkaConnections()
    super.afterAll()
  }

  it should "compose an email" in {
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

  it should "send a failed event if some template data is missing" in {
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

  it should "send a failed event if the template does not exist" in {
    sendOrchestratedEmailEvent(CommManifest(
                                 model.Service,
                                 "no-such-template",
                                 "9.9"
                               ),
                               Map.empty)
    expectNoComposedEmailEvent()
    expectNFailedEvents(1)
  }

  it should "compose an SMS" in {
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

  def orchestratedEmailEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]) =
    OrchestratedEmailV3(
      metadata(commManifest),
      internalMetadata,
      recipientEmailAddress,
      Some(profile),
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

  private def sendOrchestratedEmailEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]): Unit = {
    val event: OrchestratedEmailV3 = orchestratedEmailEvent(commManifest, templateData)
    val future = orchestratedEmailProducer.send(new ProducerRecord(orchestratedEmailTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
  }

  private def sendOrchestratedSMSEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]): Unit = {
    val event = orchestratedSMSEvent(commManifest, templateData)
    val future = orchestratedSMSProducer.send(new ProducerRecord(orchestratedSMSTopic, event))
    val result = Await.result(future, atMost = 5.seconds)
    println(s"Sent Kafka message: $result")
  }

  private def verifyComposedEmailEvent(): Unit = {
    try {
      val events = pollForEvents[ComposedEmailV2](consumer = composedEmailConsumer,
                                                  topic = composedEmailTopic,
                                                  noOfEventsExpected = 1)
      events.head.subject should be("SUBJECT Chris")
      events.head.htmlBody should be("HTML HEADER HTML BODY 1.23")
      events.head.textBody should be(Some("TEXT HEADER TEXT BODY 1.23"))
      events.head.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
      events.head.metadata.traceToken should be("transaction123")
    } finally {
      composedEmailConsumer.commitSync()
    }
  }

  private def verifyComposedSMSEvent(): Unit = {
    try {
      val events =
        pollForEvents[ComposedSMSV2](consumer = composedSMSConsumer, topic = composedSMSTopic, noOfEventsExpected = 1)
      events.head.textBody should be("SMS HEADER SMS BODY 1.23")
      events.head.metadata.traceToken should be("transaction123")
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
    try {
      val records = pollForEvents[FailedV2](consumer = failedConsumer, topic = failedTopic, noOfEventsExpected = n)
      records.size should be(n)
    } finally {
      failedConsumer.commitSync()
    }
  }
}
