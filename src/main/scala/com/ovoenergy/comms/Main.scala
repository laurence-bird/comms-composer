package com.ovoenergy.comms

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.impl.StreamLayout.Combine
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import cats.instances.either._
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.comms.email.{Composer, Interpreter}
import com.ovoenergy.comms.kafka.Kafka
import com.ovoenergy.comms.model.{ComposedEmail, Failed, OrchestratedEmail}
import com.ovoenergy.comms.repo.AmazonS3ClientWrapper
import com.ovoenergy.comms.serialisation.Serialisation._
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object Main extends App {

  val runningInDockerCompose = sys.env.get("DOCKER_COMPOSE").contains("true")

  if (runningInDockerCompose) {
    // accept the self-signed certs from the SSL proxy sitting in front of the fake S3 container
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
  }

  val log = LoggerFactory.getLogger(getClass)

  val config = ConfigFactory.load()

  val s3Client: AmazonS3ClientWrapper = {
    val awsCredentials: AWSCredentialsProvider = {
      if (runningInDockerCompose)
        new AWSStaticCredentialsProvider(new BasicAWSCredentials("service-test", "dummy"))
      else
        new AWSCredentialsProviderChain(
          new ContainerCredentialsProvider(),
          new ProfileCredentialsProvider()
        )
    }
    val underlying: AmazonS3Client =
      new AmazonS3Client(awsCredentials).withRegion(Regions.fromName(config.getString("aws.region")))
    new AmazonS3ClientWrapper(underlying)
  }

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val kafkaBootstrapServers = config.getString("kafka.bootstrap.servers")

  val input = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedEmail])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(config.getString("kafka.group.id"))
    val topic = config.getString("kafka.topics.orchestrated.email")
    Kafka.Input(topic, consumerSettings)
  }
  val composedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[ComposedEmail], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.composed.email")
    Kafka.Output(topic, producer)
  }
  val failedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[Failed], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.failed")
    Kafka.Output(topic, producer)
  }

  val interpreterFactory = Interpreter.build(s3Client) _
  val emailStream = Kafka.buildStream(input, composedEmailEventOutput, failedEmailEventOutput) { orchestratedEmail =>
    val interpreter = interpreterFactory(orchestratedEmail)
    Composer.program(orchestratedEmail).foldMap(interpreter)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info("Creating email stream")
  val control = emailStream
    .withAttributes(ActorAttributes.supervisionStrategy(decider))
    .toMat(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))(Keep.left)
    .run()
  log.info("Started email stream")

  control.isShutdown.foreach { _ =>
    log.error("ARGH! The Kafka source has shut down. Killing the JVM and nuking from orbit.")
    System.exit(1)
  }
}
