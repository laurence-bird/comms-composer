package servicetest

import java.nio.file.{Files, Paths}
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.amazonaws.util.IOUtils
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers.withThrowawayConsumerFor
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, OptionValues}
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.util.Hash
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import servicetest.util.MockTemplates
import scala.collection.JavaConverters._
import scala.language.reflectiveCalls
import scala.concurrent.duration._

class PrintServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest
    with MockTemplates
    with Arbitraries {

  behavior of "Composer print service"

  lazy val topics = Kafka.aiven

  val s3Endpoint = "http://localhost:4569"
  val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  val now = Instant.now()
  val pdfResponseByteArray = Files.readAllBytes(Paths.get("src/servicetest/resources/test.pdf"))

  val accountNumber = TemplateData.fromString("11112222")
  val ovoId = TemplateData.fromString("myOvo999")

  val orchestratedPrintEvent = OrchestratedPrintV2(
    MetadataV3(
      createdAt = now,
      eventId = "event1234",
      traceToken = "1234567890",
      templateManifest = TemplateManifest(Hash("composer-service-test"), "0.1"),
      commId = "1234",
      friendlyDescription = "very friendly",
      source = "origin",
      canary = true,
      sourceMetadata = None,
      triggerSource = "marketing",
      deliverTo = Customer("12341425")
    ),
    InternalMetadata("8989898989"),
    Some(
      CustomerProfile(
        "Bob",
        "Marley"
      )
    ),
    CustomerAddress(
      "12 Heaven Street",
      None,
      "Kingstown",
      None,
      "12345",
      Some("Jamaica")
    ),
    Map("accountNumber" -> accountNumber, "myOvoId" -> ovoId),
    Some(now.plusSeconds(100))
  )

  val templatesBucket = "ovo-comms-templates"
  lazy val s3Client = {
    val s3clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).disableChunkedEncoding().build()
    val s3: AmazonS3Client = new AmazonS3Client(new BasicAWSCredentials("service-test", "dummy"))
      .withRegion(Regions.fromName(config.getString("aws.region")))
    s3.setS3ClientOptions(s3clientOptions)
    s3.setEndpoint(s3Endpoint)
    s3
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    s3Client.createBucket("ovo-comms-templates")
    s3Client.createBucket("dev-ovo-comms-pdfs")
    uploadTemplateToS3(orchestratedPrintEvent.metadata.templateManifest, s3Client, templatesBucket)
  }

  override def afterAll(): Unit = {
    mockServerClient.close()
    s3Client.shutdown()
    super.afterAll()
  }

  it should "create a ComposedPrint event, for a valid request, archiving the rendered pdf in s3" in {
    createOKDocRaptorResponse()
    withThrowawayConsumerFor(topics.composedPrint.v2) { consumer =>
      topics.orchestratedPrint.v2.publishOnce(orchestratedPrintEvent, 10.seconds)
      val composedPrintEvent = consumer.pollFor(noOfEventsExpected = 1).head
      val key =
        s"${Hash("composer-service-test")}/${LocalDateTime
          .ofInstant(now, ZoneId.systemDefault())
          .format(dateFormatter)}/${now.toEpochMilli}-${orchestratedPrintEvent.metadata.traceToken}-${orchestratedPrintEvent.internalMetadata.internalTraceToken}.pdf"

      composedPrintEvent.metadata.traceToken shouldBe orchestratedPrintEvent.metadata.traceToken
      composedPrintEvent.internalMetadata.internalTraceToken shouldBe orchestratedPrintEvent.internalMetadata.internalTraceToken
      composedPrintEvent.pdfIdentifier should be(key)
      val s3 = s3Client

      val files: ObjectListing = s3.listObjects("dev-ovo-comms-pdfs")
      files.getObjectSummaries.asScala.foreach(println)

      val s3Object = s3.getObject("dev-ovo-comms-pdfs", key)
      IOUtils.toByteArray(s3Object.getObjectContent) should contain theSameElementsAs (pdfResponseByteArray)
    }
  }

  it should "send a failed event if docRaptor is not available" in {
    create400DocRaptorResponse()
    withThrowawayConsumerFor(topics.failed.v3) { consumer =>
      topics.orchestratedPrint.v2.publishOnce(orchestratedPrintEvent, 10.seconds)
      val failedEvent = consumer.pollFor(noOfEventsExpected = 1).head

      failedEvent.errorCode should be(CompositionError)
      failedEvent.reason should be("Failed to render pdf: Bad Request")
    }
  }

  def createOKDocRaptorResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/docs")
      )
      .respond(
        response
          .withStatusCode(200)
          .withBody(pdfResponseByteArray)
      )
  }
  def create400DocRaptorResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/docs")
      )
      .respond(
        response
          .withStatusCode(400)
          .withBody("<error>Problemo</error>")
      )
  }
}
