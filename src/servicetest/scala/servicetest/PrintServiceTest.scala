package servicetest

import java.nio.file.{Files, Paths}
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.amazonaws.util.IOUtils
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers.withThrowawayConsumerFor
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, OptionValues}
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import scala.collection.JavaConverters._
import scala.language.reflectiveCalls
//Implicits
import com.ovoenergy.comms.serialisation.Codecs._
import org.scalacheck.Shapeless._
import scala.concurrent.duration._
import scala.concurrent.duration._
import scala.io.{BufferedSource, Source}

class PrintServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest {

  behavior of "Composer print service"

  implicit val config = ConfigFactory.load("servicetest.conf")
  lazy val topics = Kafka.aiven
  val mockServerClient = new MockServerClient("localhost", 1080)
  val s3Endpoint = "http://localhost:4569"
  val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  val now = Instant.now()
  val pdfResponseByteArray = Files.readAllBytes(Paths.get("src/servicetest/resources/test.pdf"))

  override def beforeAll(): Unit = {
    super.beforeAll()
    uploadTemplateToS3()
  }

  lazy val s3Client = {
    val s3clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).disableChunkedEncoding().build()
    val s3: AmazonS3Client = new AmazonS3Client(new BasicAWSCredentials("service-test", "dummy"))
      .withRegion(Regions.fromName(config.getString("aws.region")))
    s3.setS3ClientOptions(s3clientOptions)
    s3.setEndpoint(s3Endpoint)
    s3
  }

  it should "create a ComposedPrint event, for a valid request, archiving the rendered pdf in s3" in {
    uploadTemplateToS3()
    createOKDocRaptorResponse()
    withThrowawayConsumerFor(topics.composedPrint.v1) { consumer =>
      topics.orchestratedPrint.v1.publishOnce(orchestratedPrintEvent, 10.seconds)
      val composedPrintEvent = consumer.pollFor(noOfEventsExpected = 1).head
      val key =
        s"composer-service-test/${LocalDateTime.ofInstant(now, ZoneId.systemDefault()).format(dateFormatter)}/${now.toEpochMilli}-${orchestratedPrintEvent.metadata.traceToken}-${orchestratedPrintEvent.internalMetadata.internalTraceToken}.pdf"

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
    uploadTemplateToS3()
    create400DocRaptorResponse()
    withThrowawayConsumerFor(topics.failed.v2) { consumer =>
      topics.orchestratedPrint.v1.publishOnce(orchestratedPrintEvent, 10.seconds)
      val failedEvent = consumer.pollFor(noOfEventsExpected = 1).head

      failedEvent.errorCode should be(CompositionError)
      failedEvent.reason should be("Failed to render pdf: Bad Request")
    }
  }

  val accountNumber = TemplateData.fromString("11112222")
  val ovoId = TemplateData.fromString("myOvo999")

  val orchestratedPrintEvent = OrchestratedPrint(
    MetadataV2(
      createdAt = now,
      eventId = "event1234",
      traceToken = "1234567890",
      commManifest = CommManifest(Service, "composer-service-test", "0.1"),
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

  private def uploadTemplateToS3(): Unit = {
    // disable chunked encoding to work around https://github.com/jubos/fake-s3/issues/164

    s3Client.createBucket("ovo-comms-templates")
    s3Client.createBucket("dev-ovo-comms-pdfs")

    // template
    s3Client.putObject("ovo-comms-templates",
                       "service/composer-service-test/0.1/email/subject.txt",
                       "SUBJECT {{profile.firstName}}")
    s3Client.putObject("ovo-comms-templates",
                       "service/composer-service-test/0.1/email/body.html",
                       "{{> header}} HTML BODY {{amount}}")
    s3Client.putObject("ovo-comms-templates",
                       "service/composer-service-test/0.1/email/body.txt",
                       "{{> header}} TEXT BODY {{amount}}")
    s3Client.putObject("ovo-comms-templates",
                       "service/composer-service-test/0.1/sms/body.txt",
                       "{{> header}} SMS BODY {{amount}}")
    s3Client.putObject("ovo-comms-templates",
                       "service/composer-service-test/0.1/print/body.html",
                       "Hello {{profile.firstName}}")

    // fragments
    s3Client.putObject("ovo-comms-templates", "service/fragments/email/html/header.html", "HTML HEADER")
    s3Client.putObject("ovo-comms-templates", "service/fragments/email/txt/header.txt", "TEXT HEADER")
    s3Client.putObject("ovo-comms-templates", "service/fragments/sms/txt/header.txt", "SMS HEADER")
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
