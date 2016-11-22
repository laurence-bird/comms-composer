package com.ovoenergy.comms

import java.net.InetAddress
import java.time.OffsetDateTime
import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import akka.stream.scaladsl.Sink
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import com.ovoenergy.comms.kafka.{Kafka, Serialization}
import cats.instances.either._
import com.amazonaws.auth.{
  AWSCredentialsProviderChain,
  AWSStaticCredentialsProvider,
  BasicAWSCredentials,
  ContainerCredentialsProvider
}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.email.{Composer, Interpreter}
import com.ovoenergy.comms.repo.AmazonS3ClientWrapper

import scala.util.control.NonFatal

object Main extends App {

  // TODO only do this in service tests
  System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")

  val log = LoggerFactory.getLogger(getClass)

  val config = ConfigFactory.load()

  val s3Client: AmazonS3ClientWrapper = {
    val awsCredentials = new AWSCredentialsProviderChain(
      new ContainerCredentialsProvider(),
      new ProfileCredentialsProvider(),
      new AWSStaticCredentialsProvider(new BasicAWSCredentials("service-test", "dummy"))
    )
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
      ConsumerSettings(actorSystem, new StringDeserializer, Serialization.orchestratedEmailDeserializer)
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(config.getString("kafka.group.id"))
    val topic = config.getString("kafka.topics.orchestrated.email")
    Kafka.Input(topic, consumerSettings)
  }
  val composedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, Serialization.composedEmailSerializer, bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.composed.email")
    Kafka.Output(topic, producer)
  }
  val failedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, Serialization.failedSerializer, bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.failed")
    Kafka.Output(topic, producer)
  }

  val interpreterFactory = Interpreter.build(s3Client) _
  val emailStream = Kafka.buildStream(input, composedEmailEventOutput, failedEmailEventOutput) { orchestratedEmail =>
    try {
      val interpreter = interpreterFactory(orchestratedEmail)
      Composer.program(orchestratedEmail).foldMap(interpreter)
    } catch {
      case NonFatal(e) =>
        log.info(s"OH NO! ", e)
        Left(
          Failed(Metadata(
                   OffsetDateTime.now().toString,
                   UUID.randomUUID(),
                   orchestratedEmail.metadata.customerId,
                   orchestratedEmail.metadata.transactionId,
                   orchestratedEmail.metadata.friendlyDescription,
                   "comms-composer",
                   orchestratedEmail.metadata.canary,
                   Some(orchestratedEmail.metadata)
                 ),
                 e.getMessage))
    }
  }

  val decider: Supervision.Decider = { e =>
    log.error("Restarting due to error", e)
    Supervision.Restart
  }

  emailStream.runWith(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
  log.info("Started email stream")

}
