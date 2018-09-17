package servicetest

import com.ovoenergy.comms.dockertestkit._
import com.whisk.docker.testkit.DockerReadyChecker.HttpResponseCode
import com.whisk.docker.testkit.{Container, ContainerSpec}
import org.scalatest.Suite

import scala.concurrent.duration._

object FakeS3Kit extends DockerContainerNamer

trait FakeS3Kit extends DockerTestKit with DockerHostIpProvider with DockerLogs {
  self: Suite =>

  def fakeS3Port: Int = 4569
  def fakeS3PublicPort: Int = fakeS3Container.mappedPort(fakeS3Port)
  def fakeS3Endpoint: String = s"http://$fakeS3ContainerName:$fakeS3Port"
  def fakeS3PublicEndpoint: String = s"http://$dockerHostIp:$fakeS3PublicPort"

  lazy val fakeS3ContainerName: String = ComposerKit.nextContainerName("fakeS3")

  lazy val fakeS3Container: Container = ContainerSpec("lphoward/fake-s3:latest", Some(fakeS3ContainerName))
    .withExposedPorts(4569)
    .withReadyChecker(HttpResponseCode(port = fakeS3Port, path = "/healthcheck", code = 200).looped(10, 5.seconds))
    .toContainer




}
