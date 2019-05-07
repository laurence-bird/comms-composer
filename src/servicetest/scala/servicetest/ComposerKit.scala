package com.ovoenergy.comms.composer
package servicetest

import scala.concurrent.duration._

import org.scalatest.Suite

import com.spotify.docker.client.messages.HostConfig

import com.ovoenergy.comms.dockertestkit.DockerReadyChecker.LogLineContains
import com.ovoenergy.comms.dockertestkit._

object ComposerKit extends DockerContainerNamer

trait ComposerKit extends DockerTestKit with DockerHostIpProvider {
  self: Suite
    with WiremockKit
    with DynamoDbKit
    with KafkaKit
    with SchemaRegistryKit
    with ZookeeperKit =>

  def composerPort: Int = 8080
  def composerPublicPort: Int = composerContainer.mappedPort(composerPort)

  def composerEndpoint: String = s"http://$composerContainerName:$composerPort"
  def composerPublicEndpoint: String = s"http://$dockerHostIp:$composerPublicPort"

  def composerTemplatesS3Bucket: String = "ovo-comms-test"
  def composerRenderedS3Bucket: String = "ovo-comms-test"
  def composerDeduplicationTable: String = "deduplication"
  
  def composerDocraptorEndpoint: String = s"http://$wiremockContainerName:$wiremockHttpPort/docraptor"

  lazy val composerContainerName: String = ComposerKit.nextContainerName("composer")

  lazy val composerContainer: Container = ContainerSpec(
    s"852955754882.dkr.ecr.eu-west-1.amazonaws.com/composer:${BuildInfo.version}",
    composerContainerName)
    .withEnv(
      s"DEDUPLICATION_TABLE=$composerDeduplicationTable",
      s"DEDUPLICATION_DYNAMO_DB_ENDPOINT=http://$dynamoDbContainerName:$dynamoDbPort",
      "JAVA_OPTS=-Dlogback.configurationFile=logback-local.xml",
      s"KAFKA_BOOTSTRAP_SERVERS=$kafkaPublicEndpoint",
      s"SCHEMA_REGISTRY_ENDPOINT=http://$schemaRegistryContainerName:$schemaRegistryPort",
      s"TEMPLATES_S3_BUCKET=$composerTemplatesS3Bucket",
      s"RENDERED_S3_BUCKET=$composerRenderedS3Bucket",
      s"DOCRAPTOR_ENDPOINT=$composerDocraptorEndpoint",
      s"AWS_REGION=eu-west-1",
      s"DOCRAPTOR_API_KEY=FooBar",
      s"DOCRAPTOR_IS_TEST=true",
      s"DATADOG_API_KEY=foo",
      s"DATADOG_APPLICATION_KEY=bar",
      s"METRICS_DISABLED=true",
    )
    .withExposedPorts(composerPort)
    .withVolumeBindings(
      List(HostConfig.Bind.from(s"""${sys.props("user.home")}/.aws""").to("/sbin/.aws").build()))
    .withReadyChecker(LogLineContains("Composer starting up").looped(10, 5.seconds))
    .withDependencies(zookeeperContainer, kafkaContainer, schemaRegistryContainer, dynamoDbContainer)
    .toContainer

}
