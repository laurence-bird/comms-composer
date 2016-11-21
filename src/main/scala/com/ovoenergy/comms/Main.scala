package com.ovoenergy.comms

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
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.comms.email.{Composer, Interpreter}
import com.ovoenergy.comms.repo.AmazonS3ClientWrapper

object Main extends App {

  val log = LoggerFactory.getLogger(getClass)

  val config = ConfigFactory.load()

  val s3Client: AmazonS3ClientWrapper = {
    val awsCredentials = new AWSCredentialsProviderChain(
      new ProfileCredentialsProvider(),
      InstanceProfileCredentialsProvider.getInstance()
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
    val interpreter = interpreterFactory(orchestratedEmail)
    Composer.program(orchestratedEmail).foldMap(interpreter)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Restarting due to error", e)
    Supervision.Restart
  }

  emailStream.runWith(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
  log.info("Started email stream")

}
