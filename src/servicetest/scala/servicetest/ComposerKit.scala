package servicetest

import com.ovoenergy.comms.composer.BuildInfo
import com.ovoenergy.comms.dockertestkit._
import com.spotify.docker.client.messages.HostConfig
import com.whisk.docker.testkit.ContainerSpec
import com.whisk.docker.testkit.DockerReadyChecker.HttpResponseCode
import org.scalatest.Suite

import scala.concurrent.duration._

object ComposerKit extends DockerContainerNamer

trait ComposerKit extends DockerTestKit with DockerHostIpProvider with DockerLogs {
  self: Suite with WiremockKit with DynamoDbKit with KafkaKit with SchemaRegistryKit with ZookeeperKit with FakeS3Kit=>

  val awsAccountId: String = sys.env.getOrElse(
    "AWS_ACCOUNT_ID",
    throw new IllegalStateException("AWS_ACCOUNT_ID environment variable is needed")
  )

  def composerPort: Int = 8080
  def composerPublicPort: Int = composerContainer.mappedPort(composerPort)

  def composerEndpoint: String = s"http://$composerContainerName:$composerPort"
  def composerPublicEndpoint: String = s"http://$dockerHostIp:$composerPublicPort"

  def composerDocraptorEndpoint: String = s"http://$wiremockContainerName:$wiremockHttpPort"

  lazy val composerContainerName: String = ComposerKit.nextContainerName("composer")

  lazy val composerContainer = ContainerSpec(s"$awsAccountId.dkr.ecr.eu-west-1.amazonaws.com/composer:${BuildInfo.version}", Some(composerContainerName))
    .withEnv(
      "ENV=LOCAL",
      s"KAFKA_HOSTS_AIVEN=$kafkaPublicEndpoint",
      "DOCKER_COMPOSE=true",
      s"SCHEMA_REGISTRY_URL=$schemaRegistryContainerName$schemaRegistryPort",
      "DOCRAPTOR_URL=...",
    )
    .withExposedPorts(composerPort)
    .withVolumeBindings(List(HostConfig.Bind.from(s"""${sys.props("user.home")}/.aws""").to("/sbin/.aws").build()))
    .withReadyChecker(HttpResponseCode(port = composerPort, path = "/healthcheck", code = 200).looped(10, 5.seconds))
    .toContainer

}