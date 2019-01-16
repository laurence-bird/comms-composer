package com.ovoenergy.comms.composer
package servicetest

import scala.concurrent.duration._

import org.scalatest.Suite

import com.spotify.docker.client.messages.HostConfig

import com.ovoenergy.comms.dockertestkit.DockerReadyChecker.HttpResponseCode
import com.ovoenergy.comms.dockertestkit._

object ComposerKit extends DockerContainerNamer

trait ComposerKit extends DockerTestKit with DockerHostIpProvider {
  self: Suite
    with WiremockKit
    with DynamoDbKit
    with KafkaKit
    with SchemaRegistryKit
    with ZookeeperKit =>

  val awsAccountId: String = sys.env.getOrElse(
    "AWS_ACCOUNT_ID",
    throw new IllegalStateException("AWS_ACCOUNT_ID environment variable is needed")
  )

  def composerPort: Int = 8080
  def composerPublicPort: Int = composerContainer.mappedPort(composerPort)

  def composerEndpoint: String = s"http://$composerContainerName:$composerPort"
  def composerPublicEndpoint: String = s"http://$dockerHostIp:$composerPublicPort"

  def composerTemplatesS3Bucket: String = s"ovo-comms-test"
  def composerRenderedS3Bucket: String = s"ovo-comms-test"

  def composerDocraptorEndpoint: String = s"http://$wiremockContainerName:$wiremockHttpPort/docraptor"

  lazy val composerContainerName: String = ComposerKit.nextContainerName("composer")

  lazy val composerContainer: Container = ContainerSpec(
    s"852955754882.dkr.ecr.eu-west-1.amazonaws.com/composer:${BuildInfo.version}",
    composerContainerName)
    .withEnv(
      // Kafka need to be connected on the public endpoint
      s"KAFKA_BOOTSTRAP_SERVERS=$kafkaPublicEndpoint",
      s"SCHEMA_REGISTRY_ENDPOINT=http://$schemaRegistryContainerName:$schemaRegistryPort",
      s"TEMPLATES_S3_BUCKET=$composerTemplatesS3Bucket",
      s"RENDERED_S3_BUCKET=$composerRenderedS3Bucket",
      s"DOCRAPTOR_ENDPOINT=$composerDocraptorEndpoint",
      s"AWS_REGION=eu-west-1",
      s"DOCRAPTOR_API_KEY=FooBar",
      s"DOCRAPTOR_IS_TEST=true"
    )
    .withExposedPorts(composerPort)
    .withVolumeBindings(
      List(HostConfig.Bind.from(s"""${sys.props("user.home")}/.aws""").to("/sbin/.aws").build()))
    .withReadyChecker(HttpResponseCode(port = composerPort, path = "/admin/health", code = 200)
      .looped(10, 5.seconds))
    .toContainer

}
