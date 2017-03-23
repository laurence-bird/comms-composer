package com.ovoenergy.comms

import java.util.concurrent.TimeUnit
import java.time.{Duration => JDuration}

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import cats.instances.either._
import com.ovoenergy.comms.aws.TemplateContextFactory
import com.ovoenergy.comms.email.EmailComposer
import com.ovoenergy.comms.kafka.{ComposerGraph, Retry}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.comms.serialisation.Decoders._
import com.ovoenergy.comms.sms.SMSComposer
import io.circe.generic.auto._
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

object Main extends App {

  val runningInDockerCompose = sys.env.get("DOCKER_COMPOSE").contains("true")

  if (runningInDockerCompose) {
    // accept the self-signed certs from the SSL proxy sitting in front of the fake S3 container
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
  }

  val log = LoggerFactory.getLogger(getClass)

  val config = ConfigFactory.load()

  private implicit class RichDuration(val duration: JDuration) extends AnyVal {
    def toFiniteDuration: FiniteDuration = FiniteDuration.apply(duration.toNanos, TimeUnit.NANOSECONDS)
  }

  val templateContext = TemplateContextFactory(runningInDockerCompose, config.getString("aws.region"))

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val scheduler = actorSystem.scheduler

  val kafkaBootstrapServers = config.getString("kafka.bootstrap.servers")
  val kafkaGroupId = config.getString("kafka.group.id")

  val orchestratedEmailInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedEmailV2])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.email.v2")
    ComposerGraph.Input(topic, consumerSettings)
  }

  val orchestratedSMSInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedSMS])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.sms")
    ComposerGraph.Input(topic, consumerSettings)
  }

  val kafkaProducerRetryConfig = Retry.RetryConfig(
    attempts = config.getInt("kafka.producer.retry.attempts"),
    backoff = Retry.Backoff.exponential(
      config.getDuration("kafka.producer.retry.initialInterval").toFiniteDuration,
      config.getDouble("kafka.producer.retry.exponent")
    )
  )

  // These outputs are only lazy for the sake of the service tests.
  // We need to construct the producer after the topic has been created,
  // otherwise the tests randomly fail.
  lazy val composedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[ComposedEmail], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.composed.email")
    ComposerGraph.Output(topic, producer, kafkaProducerRetryConfig)
  }

  lazy val composedSMSEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[ComposedSMS], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.composed.sms")
    ComposerGraph.Output(topic, producer, kafkaProducerRetryConfig)
  }

  lazy val failedEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[Failed], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.failed")
    ComposerGraph.Output(topic, producer, kafkaProducerRetryConfig)
  }

  val emailInterpreter = Interpreters.emailInterpreter(templateContext)
  val emailGraph = ComposerGraph.build(orchestratedEmailInput, composedEmailEventOutput, failedEventOutput) {
    (orchestratedEmail: OrchestratedEmailV2) =>
      EmailComposer.program(orchestratedEmail).foldMap(emailInterpreter)
  }

  val smsInterpreter = Interpreters.smsInterpreter(templateContext)
  val smsGraph = ComposerGraph.build(orchestratedSMSInput, composedSMSEventOutput, failedEventOutput) {
    (orchestratedSMS: OrchestratedSMS) =>
      SMSComposer.program(orchestratedSMS).foldMap(smsInterpreter)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info("Creating graphs")

  Seq(
    (emailGraph, "email"),
    (smsGraph, "SMS")
  ) foreach {
    case (graph, description) =>
      val control = graph
        .withAttributes(ActorAttributes.supervisionStrategy(decider))
        .to(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
        .run()

      log.info(s"Started $description graph")

      control.isShutdown.foreach { _ =>
        log.error("ARGH! The Kafka source has shut down. Killing the JVM and nuking from orbit.")
        System.exit(1)
      }
  }
}
