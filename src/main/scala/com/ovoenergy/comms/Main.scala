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

object Main extends App {

  val log = LoggerFactory.getLogger(getClass)

  val config = ConfigFactory.load()

//  val awsCredentials = new AWSCredentialsProviderChain(
//    new ProfileCredentialsProvider(),
//    new InstanceProfileCredentialsProvider()
//  )

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

  val stream = Kafka.buildStream(input, composedEmailEventOutput, failedEmailEventOutput) { orchestratedEmail =>
    val interpreter = Interpreter.build(orchestratedEmail)
    Composer.program(orchestratedEmail).foldMap(interpreter)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Restarting due to error", e)
    Supervision.Restart
  }

  stream.runWith(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
  log.info("Started stream")

}
