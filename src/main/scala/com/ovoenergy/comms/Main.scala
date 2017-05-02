package com.ovoenergy.comms

import java.util.concurrent.TimeUnit
import java.time.{Duration => JDuration}

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import cats.instances.either._
import com.ovoenergy.comms.aws.TemplateContextFactory
import com.ovoenergy.comms.email.EmailComposer
import com.ovoenergy.comms.kafka.{ComposerGraph, ComposerGraphLegacy, Producer, Retry}
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

  val orchestratedEmailLegacyInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedEmailV2])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.email.v2")
    ComposerGraphLegacy.Input(topic, consumerSettings)
  }

  val orchestratedEmailInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedEmailV3])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.email.v3")
    ComposerGraph.Input(topic, consumerSettings)
  }

  val orchestratedSMSLegacyInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedSMS])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.sms.v1")
    ComposerGraphLegacy.Input(topic, consumerSettings)
  }

  val orchestratedSMSInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedSMSV2])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.sms.v2")
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
  lazy val composedEmailEventProducer = {
    Producer[ComposedEmailV2](
      hosts = kafkaBootstrapServers,
      topic = config.getString("kafka.topics.composed.email.v2"),
      serialiser = avroSerializer[ComposedEmailV2],
      retryConfig = kafkaProducerRetryConfig
    )
  }

  lazy val composedSMSEventProducer = {
    Producer[ComposedSMSV2](
      hosts = kafkaBootstrapServers,
      topic = config.getString("kafka.topics.composed.sms.v2"),
      serialiser = avroSerializer[ComposedSMSV2],
      retryConfig = kafkaProducerRetryConfig
    )
  }

  lazy val failedEventProducer = {
    Producer[FailedV2](
      hosts = kafkaBootstrapServers,
      topic = config.getString("kafka.topics.failed.v2"),
      serialiser = avroSerializer[FailedV2],
      retryConfig = kafkaProducerRetryConfig
    )
  }

  val emailInterpreter = Interpreters.emailInterpreter(templateContext)
  val emailComposer = (orchestratedEmail: OrchestratedEmailV3) =>
    EmailComposer.program(orchestratedEmail).foldMap(emailInterpreter)

  val smsInterpreter = Interpreters.smsInterpreter(templateContext)
  val smsComposer = (orchestratedSMS: OrchestratedSMSV2) =>
    SMSComposer.program(orchestratedSMS).foldMap(smsInterpreter)

  private def metadataToV2(metadata: Metadata): MetadataV2 = {
    MetadataV2(
      createdAt = metadata.createdAt,
      eventId = metadata.eventId,
      traceToken = metadata.traceToken,
      commManifest = metadata.commManifest,
      friendlyDescription = metadata.friendlyDescription,
      source = metadata.source,
      canary = metadata.canary,
      sourceMetadata = metadata.sourceMetadata.map(metadataToV2),
      triggerSource = metadata.triggerSource
    )
  }

  val emailGraphLegacy =
    ComposerGraphLegacy.build(orchestratedEmailLegacyInput, composedEmailEventProducer, failedEventProducer) {
      (orchestratedEmail: OrchestratedEmailV2) =>
        val orchestratedEmailV3 = OrchestratedEmailV3(
          metadata = metadataToV2(orchestratedEmail.metadata),
          internalMetadata = orchestratedEmail.internalMetadata,
          recipientEmailAddress = orchestratedEmail.recipientEmailAddress,
          customerProfile = Some(orchestratedEmail.customerProfile),
          templateData = orchestratedEmail.templateData,
          expireAt = orchestratedEmail.expireAt
        )
        emailComposer(orchestratedEmailV3)
    }

  val smsGraphLegacy =
    ComposerGraphLegacy.build(orchestratedSMSLegacyInput, composedSMSEventProducer, failedEventProducer) {
      (orchestratedSMS: OrchestratedSMS) =>
        val orchestratedSMSV2 = OrchestratedSMSV2(
          metadata = metadataToV2(orchestratedSMS.metadata),
          internalMetadata = orchestratedSMS.internalMetadata,
          recipientPhoneNumber = orchestratedSMS.recipientPhoneNumber,
          customerProfile = Some(orchestratedSMS.customerProfile),
          templateData = orchestratedSMS.templateData,
          expireAt = orchestratedSMS.expireAt
        )
        smsComposer(orchestratedSMSV2)
    }

  val emailGraph = ComposerGraph.build(orchestratedEmailInput, composedEmailEventProducer, failedEventProducer) {
    (orchestratedEmail: OrchestratedEmailV3) =>
      emailComposer(orchestratedEmail)
  }

  val smsGraph = ComposerGraph.build(orchestratedSMSInput, composedSMSEventProducer, failedEventProducer) {
    (orchestratedSMS: OrchestratedSMSV2) =>
      smsComposer(orchestratedSMS)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info("Creating graphs")

  Seq(
    (emailGraphLegacy, "email-legacy"),
    (smsGraphLegacy, "SMS-legacy"),
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
