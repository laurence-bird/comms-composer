package servicetest

import java.time.OffsetDateTime
import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.composer.http.HttpServerConfig
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.OrchestratedEmailV3.schemaFor
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.scalatest.{Failed => _, _}
import shapeless.Coproduct

import scala.concurrent.duration._
import scala.language.reflectiveCalls
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.dsl._

class HttpServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest {

  implicit val config: Config = ConfigFactory.load("servicetest.conf")

  implicit val patience: PatienceConfig = PatienceConfig(5.minutes, 1.second)

  behavior of "Composer HTTP service"

  it should "respond OK to /admin/health" in newHttpClient { client =>
    whenReady(client.status(Request(GET, Uri.unsafeFromString(s"$composerHttpEndpoint/admin/health"))).unsafeRunAsyncFuture()) { status =>
      status shouldBe Ok
    }
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
