package servicetest

import java.nio.file.{Files, Paths}
import java.time.OffsetDateTime
import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.composer.http.RenderRestApi.{ErrorResponse, RenderRequest, RenderResponse}
import com.ovoenergy.comms.model.email.OrchestratedEmailV3.schemaFor
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._
import com.typesafe.config.{Config, ConfigFactory}
import fs2.Task
import io.circe.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.scalatest.{Failed => _, _}
import shapeless.Coproduct

import scala.concurrent.duration._
import scala.language.reflectiveCalls
import org.http4s.{Status, _}
import org.http4s.client.Client
import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.dsl._
import fs2.Task
import org.http4s.{HttpService, Request, Response}
import org.http4s.dsl._
import org.http4s.circe._
import org.http4s.client._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.implicits._
import com.ovoenergy.comms.composer.print.RenderedPrintPdf
import com.ovoenergy.comms.model.TemplateData
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

class HttpServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest {

  implicit val config: Config = ConfigFactory.load("servicetest.conf")

  implicit val patience: PatienceConfig = PatienceConfig(5.minutes, 1.second)

  val pdfResponseByteArray = Files.readAllBytes(Paths.get("src/servicetest/resources/test.pdf"))

  val s3Endpoint = "http://localhost:4569"

  lazy val s3Client = {
    val s3clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).disableChunkedEncoding().build()
    val s3: AmazonS3Client = new AmazonS3Client(new BasicAWSCredentials("service-test", "dummy"))
      .withRegion(Regions.fromName(config.getString("aws.region")))
    s3.setS3ClientOptions(s3clientOptions)
    s3.setEndpoint(s3Endpoint)
    s3
  }

  override def beforeAll() = {
    super.beforeAll()
    uploadTemplateToS3()
    createOKDocRaptorResponse()
  }

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

  behavior of "Composer HTTP service"

  it should "respond OK to /admin/health" in newHttpClient { client =>
    whenReady(
      client
        .status(Request(GET, Uri.unsafeFromString(s"$composerHttpEndpoint/admin/health")))
        .unsafeRunAsyncFuture()) { status =>
      status shouldBe Ok
    }
  }

  it should "respond OK to /render/canary/1.0/Service/print" in newHttpClient { client =>
    import io.circe.syntax._

    val templateData = {
      val accountNumber = TemplateData.fromString("11112222")
      val ovoId = TemplateData.fromString("myOvo999")
      val firstName = TemplateData.fromString("David")
      val lastName = TemplateData.fromString("Gilmour")
      val profile = TemplateData.fromMap(Map("firstName" -> firstName, "lastName" -> lastName))
      Map(
        "accountNumber" -> accountNumber,
        "myOvoId" -> ovoId,
        "profile" -> profile
      )
    }

    val renderRequest: RenderRequest = RenderRequest(templateData)
    val req: Task[Request] =
      POST(Uri.unsafeFromString(s"$composerHttpEndpoint/render/composer-service-test/0.1/Service/print"),
           renderRequest.asJson)

    whenReady {
      import com.ovoenergy.comms.composer.http.RenderRestApi.RenderResponse._
      client
        .fetch(req)(_.as(jsonOf[RenderResponse]))
        .unsafeRunAsyncFuture()
    } { r =>
      r.renderedPrint.pdfBody should contain theSameElementsAs pdfResponseByteArray
    }
  }

  it should "return an appropriate error if invalid comm type is passed in URL" in newHttpClient { client =>
    import io.circe.syntax._

    val templateData = {
      val accountNumber = TemplateData.fromString("11112222")
      val ovoId = TemplateData.fromString("myOvo999")
      val firstName = TemplateData.fromString("David")
      val lastName = TemplateData.fromString("Gilmour")
      val profile = TemplateData.fromMap(Map("firstName" -> firstName, "lastName" -> lastName))
      Map(
        "accountNumber" -> accountNumber,
        "myOvoId" -> ovoId,
        "profile" -> profile
      )
    }

    val renderRequest: RenderRequest = RenderRequest(templateData)
    val req: Task[Request] =
      POST(Uri.unsafeFromString(s"$composerHttpEndpoint/render/composer-service-test/0.1/invalid/print"),
           renderRequest.asJson)

    whenReady {
      import com.ovoenergy.comms.composer.http.RenderRestApi.RenderResponse._
      client
        .status(req)
        .unsafeRunAsyncFuture()
    } { status =>
      status shouldBe NotFound
    }
  }

  it should "return an appropriate error if template data is missing fields" in newHttpClient { client =>
    import io.circe.syntax._

    val templateData = {
      val accountNumber = TemplateData.fromString("11112222")
      val ovoId = TemplateData.fromString("myOvo999")
      Map(
        "accountNumber" -> accountNumber,
        "myOvoId" -> ovoId,
      )
    }

    val renderRequest: RenderRequest = RenderRequest(templateData)
    val req: Task[Request] =
      POST(Uri.unsafeFromString(s"$composerHttpEndpoint/render/composer-service-test/0.1/Service/print"),
           renderRequest.asJson)

    whenReady {
      import com.ovoenergy.comms.composer.http.RenderRestApi._
      client
        .fetch(req)(_.as(jsonOf[ErrorResponse]))
        .unsafeRunAsyncFuture()
    } { error =>
      error.message shouldBe "The template referenced the following non-existent keys:\n - profile.firstName\n           "
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

  def newHttpClient[A](f: Client => A): A = {
    val httpClient: Client = PooledHttp1Client()
    try {
      f(httpClient)
    } finally {
      httpClient.shutdownNow()
    }
  }
}
