package servicetest

import java.time.OffsetDateTime
import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.scalatest.{Failed => _, _}
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._
import shapeless.Coproduct
import scala.concurrent.duration._
import com.ovoenergy.comms.templates.util.Hash
import servicetest.util.MockTemplates

import scala.language.reflectiveCalls

//class AivenServiceTest
//    extends FlatSpec
//    with Matchers
//    with OptionValues
//    with BeforeAndAfterAll
//    with DockerIntegrationTest
//    with MockTemplates {
//
//  behavior of "Composer service"
//
//  val s3Endpoint = "http://localhost:4569"
//
//  lazy val s3Client = {
//    val s3clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).disableChunkedEncoding().build()
//    val s3: AmazonS3Client = new AmazonS3Client(new BasicAWSCredentials("service-test", "dummy"))
//      .withRegion(Regions.fromName(config.getString("aws.region")))
//    s3.setS3ClientOptions(s3clientOptions)
//    s3.setEndpoint(s3Endpoint)
//    s3
//  }
//
//  final private val validTemplateCommManifest = TemplateManifest(
//    Hash("composer-service-test"),
//    "0.1"
//  )
//
//  final private val invalidTemplateCommManifest = TemplateManifest(
//    Hash("no-such-template"),
//    "9.9"
//  )
//
//  override def beforeAll(): Unit = {
//    val templatesBucket = "ovo-comms-templates"
//    super.beforeAll()
//    s3Client.createBucket(templatesBucket)
//    s3Client.createBucket("dev-ovo-comms-pdfs")
//    uploadTemplateToS3(validTemplateCommManifest, s3Client, templatesBucket)
//  }
//
//  override def afterAll(): Unit = {
//    mockServerClient.close()
//    s3Client.shutdown()
//    super.afterAll()
//  }
//
//  it should "compose an email" in {
//    withThrowawayConsumerFor(Kafka.aiven.composedEmail.v4, Kafka.aiven.failed.v3) {
//      (composedConsumer, failedConsumer) =>
//        sendOrchestratedEmailEvent(validTemplateCommManifest,
//                                   Map(
//                                     "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
//                                   ))
//        verifyComposedEmailEvent(composedConsumer)
//        failedConsumer.checkNoMessages(5.seconds)
//    }
//  }
//
//  it should "send a failed event if some template data is missing" in {
//    withThrowawayConsumerFor(Kafka.aiven.composedEmail.v4, Kafka.aiven.failed.v3) {
//      (composedConsumer, failedConsumer) =>
//        sendOrchestratedEmailEvent(
//          validTemplateCommManifest,
//          Map.empty
//        )
//        composedConsumer.checkNoMessages(5.seconds)
//        val failedResult = failedConsumer.pollFor(noOfEventsExpected = 1)
//        failedResult.foreach { failed =>
//          failed.errorCode shouldBe MissingTemplateData
//        }
//    }
//  }
//
//  it should "send a failed event if the template does not exist" in {
//    withThrowawayConsumerFor(Kafka.aiven.composedEmail.v4, Kafka.aiven.failed.v3) {
//      (composedConsumer, failedConsumer) =>
//        sendOrchestratedEmailEvent(invalidTemplateCommManifest, Map.empty)
//        composedConsumer.checkNoMessages(5.seconds)
//        val failedResult = failedConsumer.pollFor(noOfEventsExpected = 1)
//        failedResult.foreach { failed =>
//          failed.errorCode shouldBe TemplateDownloadFailed
//        }
//    }
//  }
//
//  it should "compose an SMS" in {
//    withThrowawayConsumerFor(Kafka.aiven.composedSms.v4, Kafka.aiven.failed.v3) { (composedConsumer, failedConsumer) =>
//      sendOrchestratedSMSEvent(validTemplateCommManifest,
//                               Map(
//                                 "amount" -> TemplateData(Coproduct[TemplateData.TD]("1.23"))
//                               ))
//      verifyComposedSMSEvent(composedConsumer)
//      failedConsumer.checkNoMessages(5.seconds)
//    }
//  }
//
//  def metadata(templateManifest: TemplateManifest) = MetadataV3(
//    createdAt = OffsetDateTime.now().toInstant,
//    eventId = UUID.randomUUID().toString,
//    traceToken = "transaction123",
//    deliverTo = Customer("customer123"),
//    templateManifest = templateManifest,
//    commId = "1234",
//    friendlyDescription = "composer service test",
//    source = "ServiceSpec",
//    canary = true,
//    sourceMetadata = None,
//    triggerSource = "SomeTriggerSource"
//  )
//
//  val internalMetadata = InternalMetadata("yoooo")
//  val recipientEmailAddress = "chris.birchall@ovoenergy.com"
//  val recipientPhoneNumber = "+447123456789"
//  val profile = CustomerProfile(
//    "Chris",
//    "Birchall"
//  )
//
//  def orchestratedEmailEvent(templateManifest: TemplateManifest, templateData: Map[String, TemplateData]) =
//    OrchestratedEmailV4(
//      metadata(templateManifest),
//      internalMetadata,
//      recipientEmailAddress,
//      Some(profile),
//      templateData,
//      expireAt = None
//    )
//
//  def orchestratedSMSEvent(templateManifest: TemplateManifest, templateData: Map[String, TemplateData]) =
//    OrchestratedSMSV3(
//      metadata(templateManifest),
//      internalMetadata,
//      recipientPhoneNumber,
//      Some(profile),
//      templateData,
//      expireAt = None
//    )
//
//  private def sendOrchestratedEmailEvent(templateManifest: TemplateManifest,
//                                         templateData: Map[String, TemplateData]): Unit = {
//    val event: OrchestratedEmailV4 = orchestratedEmailEvent(templateManifest, templateData)
//    Kafka.aiven.orchestratedEmail.v4.publishOnce(event, 5.seconds)
//  }
//
//  private def sendOrchestratedSMSEvent(templateManifest: TemplateManifest,
//                                       templateData: Map[String, TemplateData]): Unit = {
//    val event = orchestratedSMSEvent(templateManifest, templateData)
//    Kafka.aiven.orchestratedSMS.v3.publishOnce(event, 5.seconds)
//  }
//
//  private def verifyComposedEmailEvent(consumer: KafkaConsumer[String, Option[ComposedEmailV4]]): Unit = {
//    val events = consumer.pollFor(noOfEventsExpected = 1)
//    events.head.subject should be("SUBJECT Chris")
//    events.head.htmlBody should be("HTML HEADER HTML BODY 1.23")
//    events.head.textBody should be(Some("TEXT HEADER TEXT BODY 1.23"))
//    events.head.sender should be("Ovo Energy <no-reply@ovoenergy.com>")
//    events.head.metadata.traceToken should be("transaction123")
//  }
//
//  private def verifyComposedSMSEvent(consumer: KafkaConsumer[String, Option[ComposedSMSV4]]): Unit = {
//    val events = consumer.pollFor(noOfEventsExpected = 1)
//    events.head.textBody should be("SMS HEADER SMS BODY 1.23")
//    events.head.metadata.traceToken should be("transaction123")
//  }
//}
