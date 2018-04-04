package servicetest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import cats.data.Kleisli
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.effect.IO
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.composer.http.RenderRestApi.{ErrorResponse, RenderRequest, RenderResponse}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Failed => _, _}

import scala.concurrent.duration._
import scala.language.reflectiveCalls
import org.http4s.{Status, _}
import org.http4s.client.Client
import org.http4s.client.blaze.Http1Client
import org.http4s.Request
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.client._
import cats.implicits._
import com.ovoenergy.comms.model.TemplateData
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

class HttpServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest {

//  override val config: Config = ConfigFactory.load("servicetest.conf")

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
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"$composerHttpEndpoint/admin/health"))

    whenReady {
      client.status(req).unsafeToFuture()
    } { status =>
      status shouldBe Status.Ok
    }
  }

  it should "respond OK to /render/canary/1.0/Service/print" in newHttpClient { client =>
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

    val req = Request[IO](
      Method.POST,
      Uri.unsafeFromString(s"$composerHttpEndpoint/render/composer-service-test/0.1/Service/print"),
      body = fs2.Stream
        .emit(renderRequest.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    whenReady {
      client
        .fetch(req)(_.decodeJson[RenderResponse])
        .unsafeToFuture()
    } { r: RenderResponse =>
      r.renderedPrint.pdfBody should contain theSameElementsAs pdfResponseByteArray
    }
  }

  it should "return an appropriate error if invalid comm type is passed in URL" in newHttpClient { client =>
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

    val req = Request[IO](
      Method.POST,
      Uri.unsafeFromString(s"$composerHttpEndpoint/render/composer-service-test/0.1/invalid/print"),
      body = fs2.Stream
        .emit(renderRequest.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    whenReady {
      client.status(req).unsafeToFuture()
    } { status =>
      status shouldBe Status.NotFound
    }
  }

  it should "return an appropriate error if template data is missing fields" in newHttpClient { client =>
    val templateData = {
      val accountNumber = TemplateData.fromString("11112222")
      val ovoId = TemplateData.fromString("myOvo999")
      Map(
        "accountNumber" -> accountNumber,
        "myOvoId" -> ovoId
      )
    }

    val renderRequest: RenderRequest = RenderRequest(templateData)

    val req = Request[IO](
      Method.POST,
      Uri.unsafeFromString(s"$composerHttpEndpoint/render/composer-service-test/0.1/Service/print"),
      body = fs2.Stream
        .emit(renderRequest.asJson)
        .flatMap(json => fs2.Stream.emits(json.noSpaces.getBytes(StandardCharsets.UTF_8).toSeq))
        .covary[IO]
    )

    whenReady {
      client.fetch(req)(_.decodeJson[ErrorResponse]).unsafeToFuture()
    } { error =>
      error.message shouldBe "The template referenced the following non-existent keys: [profile.firstName]"
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

  def newHttpClient[A](f: Client[IO] => A): A = {
    val httpClient: IO[Client[IO]] = Http1Client[IO]()

    val s: fs2.Stream[IO, A] =
      fs2.Stream.bracket(Http1Client[IO]())(client => fs2.Stream.emit(f(client)), client => client.shutdown)

    s.compile.toVector.unsafeRunSync().head
  }
}
