package servicetest

import java.time.OffsetDateTime
import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.scalatest.{Failed => _, _}
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._
import shapeless.Coproduct

import scala.concurrent.Await
import scala.concurrent.duration._

class AivenServiceTest
  extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest {

  behavior of "Composer service"

  val config: Config =
    ConfigFactory.load(ConfigParseOptions.defaults(), ConfigResolveOptions.defaults().setAllowUnresolved(true))

  val s3Endpoint = "http://localhost:4569"

  override def beforeAll(): Unit = {
    super.beforeAll()
    uploadTemplateToS3()
  }

  it should "compose an email" in {
    withThrowawayConsumerFor(Kafka.aiven.composedEmail.v2, Kafka.aiven.failed.v2) {
      (composedConsumer, failedConsumer) =>
        sendOrchestratedEmailEvent(CommManifest(
          model.Service,
          "composer-service-test",
          "0.1"
        ),
          Map(
            "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
          ))
        verifyComposedEmailEvent(composedConsumer)
        failedConsumer.checkNoMessages(5.seconds)
    }
  }

  it should "send a failed event if some template data is missing" in {
    withThrowawayConsumerFor(Kafka.aiven.composedEmail.v2, Kafka.aiven.failed.v2) {
      (composedConsumer, failedConsumer) =>
        sendOrchestratedEmailEvent(
          CommManifest(
            model.Service,
            "composer-service-test",
            "0.1"
          ),
          Map.empty
        )
        composedConsumer.checkNoMessages(5.seconds)
        val failedResult = failedConsumer.pollFor(noOfEventsExpected = 1)
        failedResult.foreach { failed =>
          failed.errorCode shouldBe MissingTemplateData
        }
    }
  }

  it should "send a failed event if the template does not exist" in {
    withThrowawayConsumerFor(Kafka.aiven.composedEmail.v2, Kafka.aiven.failed.v2) {
      (composedConsumer, failedConsumer) =>
        sendOrchestratedEmailEvent(CommManifest(
          model.Service,
          "no-such-template",
          "9.9"
        ),
          Map.empty)
        composedConsumer.checkNoMessages(5.seconds)
        val failedResult = failedConsumer.pollFor(noOfEventsExpected = 1)
        failedResult.foreach { failed =>
          failed.errorCode shouldBe TemplateDownloadFailed
        }
    }
  }

  it should "compose an SMS" in {
    withThrowawayConsumerFor(Kafka.aiven.composedSms.v2, Kafka.aiven.failed.v2) {
      (composedConsumer, failedConsumer) =>
        sendOrchestratedSMSEvent(CommManifest(
          model.Service,
          "composer-service-test",
          "0.1"
        ),
          Map(
            "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
          ))
        verifyComposedSMSEvent(composedConsumer)
        failedConsumer.checkNoMessages(5.seconds)
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
    Kafka.aiven.orchestratedEmail.v3.publishOnce(event, 5.seconds)
  }

  private def sendOrchestratedSMSEvent(commManifest: CommManifest, templateData: Map[String, TemplateData]): Unit = {
    val event = orchestratedSMSEvent(commManifest, templateData)
    Kafka.aiven.orchestratedSMS.v2.publishOnce(event, 5.seconds)
  }

  private def verifyComposedEmailEvent(consumer: KafkaConsumer[String, Option[ComposedEmailV2]]): Unit = {
    val events = consumer.pollFor(noOfEventsExpected = 1)
    events.head.subject should be("SUBJECT Chris")
    events.head.htmlBody should be("HTML HEADER HTML BODY 1.23")
    events.head.textBody should be(Some("TEXT HEADER TEXT BODY 1.23"))
    events.head.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
    events.head.metadata.traceToken should be("transaction123")
  }

  private def verifyComposedSMSEvent(consumer: KafkaConsumer[String, Option[ComposedSMSV2]]): Unit = {
    val events = consumer.pollFor(noOfEventsExpected = 1)
    events.head.textBody should be("SMS HEADER SMS BODY 1.23")
    events.head.metadata.traceToken should be("transaction123")
  }
}
