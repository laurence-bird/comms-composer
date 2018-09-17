package servicetest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import cats.effect.IO
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.composer.http.RenderRestApi.{ErrorResponse, RenderRequest, RenderResponse}
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
import com.ovoenergy.comms.model.{CommManifest, TemplateData, TemplateManifest, Service => ServiceComm}
import com.ovoenergy.comms.templates.util.Hash
import servicetest.util.MockTemplates

class HttpServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest
    with MockTemplates {

  implicit val patience: PatienceConfig = PatienceConfig(5.minutes, 1.second)


  behavior of "Composer HTTP service"

  it should "respond OK to /admin/health" in newHttpClient { client =>
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"$composerPublicEndpoint/admin/health"))

    whenReady {
      client.status(req).unsafeToFuture()
    } { status =>
      status shouldBe Status.Ok
    }
  }


  it should "return an appropriate error if template data is missing fields" in newHttpClient {
    client =>
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
        Uri.unsafeFromString(
          s"$composerPublicEndpoint/render/${validTemplateCommManifest.id}/${validTemplateCommManifest.version}/print"),
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

  def newHttpClient[A](f: Client[IO] => A): A = {

    val s: fs2.Stream[IO, A] =
      fs2.Stream.bracket(Http1Client[IO]())(client => fs2.Stream.emit(f(client)), client => client.shutdown)

    s.compile.toVector.unsafeRunSync().head
  }
}
