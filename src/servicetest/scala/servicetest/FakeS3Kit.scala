package com.ovoenergy.comms.composer
package servicetest

import com.ovoenergy.comms.dockertestkit.DockerReadyChecker.HttpResponseCode
import com.ovoenergy.comms.dockertestkit._
import org.scalatest.Suite

import scala.concurrent.duration._

object FakeS3Kit extends DockerContainerNamer

trait FakeS3Kit extends DockerTestKit with DockerHostIpProvider with DockerLogs {
  self: Suite =>

  def fakeS3Port: Int = 80
  def fakeS3PublicPort: Int = fakeS3Container.mappedPort(fakeS3Port)
  def fakeS3Endpoint: String = s"http://$fakeS3ContainerName:$fakeS3Port"
  def fakeS3PublicEndpoint: String = s"http://$dockerHostIp:$fakeS3PublicPort"

  lazy val fakeS3ContainerName: String = FakeS3Kit.nextContainerName("fakeS3")

  lazy val fakeS3Container: Container = ContainerSpec("scireum/s3-ninja:5.0.1", fakeS3ContainerName)
    .withExposedPorts(fakeS3Port)
    .withReadyChecker(HttpResponseCode(port = fakeS3Port, path = "/", code = 200).looped(10, 5.seconds))
    .toContainer

}