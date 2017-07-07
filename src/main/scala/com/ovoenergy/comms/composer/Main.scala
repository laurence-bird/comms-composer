package com.ovoenergy.comms.composer

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.time.{OffsetDateTime, Duration => JDuration}

import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ProducerSettings}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import com.ovoenergy.comms.composer.aws.TemplateContextFactory
import com.ovoenergy.comms.composer.email.EmailComposer
import com.ovoenergy.comms.composer.kafka.{ComposerGraph, Producer, Retry}
import com.ovoenergy.comms.model.email.{ComposedEmailV2, OrchestratedEmailV3}
import com.ovoenergy.comms.model.sms.{ComposedSMSV2, OrchestratedSMSV2}
import com.ovoenergy.comms.model.{Customer, FailedV2, Metadata, MetadataV2}
import com.ovoenergy.comms.serialisation.Serialisation.{avroDeserializer, avroSerializer}
import com.ovoenergy.comms.composer.sms.SMSComposer
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import cats.instances.either._
import com.ovoenergy.comms.akka.streams.Factory
import com.ovoenergy.comms.akka.streams.Factory.{KafkaConfig, consumerSettings}
import com.ovoenergy.kafka.serialization.avro.SchemaRegistryClientSettings
import com.ovoenergy.comms.serialisation._
// Implicits
import com.ovoenergy.comms.serialisation.Codecs._
import io.circe.generic.auto._

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
  val aivenHosts = config.getString("kafka.aiven.hosts")
  val kafkaGroupId = config.getString("kafka.group.id")

  private val schemaRegistryEndpoint = config.getString("kafka.aiven.schema_registry.url")
  private val schemaRegistryUsername = config.getString("kafka.aiven.schema_registry.username")
  private val schemaRegistryPassword = config.getString("kafka.aiven.schema_registry.password")
  private val orchestratedEmailTopic = config.getString("kafka.topics.orchestrated.email.v3")

  val kafkaSSLConfig = {
    if (config.getBoolean("kafka.ssl.enabled")) {
      Some(
        Factory.SSLConfig(
          keystoreLocation = Paths.get(config.getString("kafka.ssl.keystore.location")),
          keystoreType = Factory.StoreType.PKCS12,
          keystorePassword = config.getString("kafka.ssl.keystore.password"),
          keyPassword = config.getString("kafka.ssl.key.password"),
          truststoreLocation = Paths.get(config.getString("kafka.ssl.truststore.location")),
          truststoreType = Factory.StoreType.JKS,
          truststorePassword = config.getString("kafka.ssl.truststore.password")
        ))
    } else None
  }

  val schemaRegistryClientSettings =
    SchemaRegistryClientSettings(schemaRegistryEndpoint, schemaRegistryUsername, schemaRegistryPassword)

  val aivenOrchestratedEmailInput = {
    val kafkaConfig = KafkaConfig(kafkaGroupId, aivenHosts, orchestratedEmailTopic, kafkaSSLConfig)
    val settings = consumerSettings[OrchestratedEmailV3](schemaRegistryClientSettings, kafkaConfig)
    ComposerGraph.Input(orchestratedEmailTopic, settings)
  }
  val legacyOrchestratedEmailInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedEmailV3])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)

    ComposerGraph.Input(orchestratedEmailTopic, consumerSettings)
  }

  val legacyOrchestratedSMSInput = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedSMSV2])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(kafkaGroupId)
    val topic = config.getString("kafka.topics.orchestrated.sms.v2")
    ComposerGraph.Input(topic, consumerSettings)
  }

  val aivenOrchestratedSMSInput = {
    val topic = config.getString("kafka.topics.orchestrated.sms.v2")
    val kafkaConfig = KafkaConfig(kafkaGroupId, aivenHosts, topic, kafkaSSLConfig)
    val settings = consumerSettings[OrchestratedSMSV2](schemaRegistryClientSettings, kafkaConfig)
    ComposerGraph.Input(topic, settings)
  }

  val kafkaProducerRetryConfig = Retry.RetryConfig(
    attempts = config.getInt("kafka.producer.retry.attempts"),
    backoff = Retry.Backoff.exponential(
      config.getDuration("kafka.producer.retry.initialInterval").toFiniteDuration,
      config.getDouble("kafka.producer.retry.exponent")
    )
  )

  val composedEmailEventProducer = {
    Producer[ComposedEmailV2](
      hosts = aivenHosts,
      topic = config.getString("kafka.topics.composed.email.v2"),
      schemaRegistryClientSettings = schemaRegistryClientSettings,
      retryConfig = kafkaProducerRetryConfig,
      sslConfig = kafkaSSLConfig
    )
  }

  val composedSMSEventProducer = {
    val composedSmsTopic = config.getString("kafka.topics.composed.sms.v2")
    Producer[ComposedSMSV2](
      hosts = aivenHosts,
      topic = composedSmsTopic,
      schemaRegistryClientSettings = schemaRegistryClientSettings,
      retryConfig = kafkaProducerRetryConfig,
      sslConfig = kafkaSSLConfig
    )
  }

  val failedEventProducer = {
    val failedTopic = config.getString("kafka.topics.failed.v2")
    Producer[FailedV2](
      hosts = aivenHosts,
      topic = failedTopic,
      schemaRegistryClientSettings = schemaRegistryClientSettings,
      retryConfig = kafkaProducerRetryConfig,
      sslConfig = kafkaSSLConfig
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
      createdAt = OffsetDateTime.parse(metadata.createdAt).toInstant,
      eventId = metadata.eventId,
      traceToken = metadata.traceToken,
      commManifest = metadata.commManifest,
      deliverTo = Customer(metadata.customerId),
      friendlyDescription = metadata.friendlyDescription,
      source = metadata.source,
      canary = metadata.canary,
      sourceMetadata = metadata.sourceMetadata.map(metadataToV2),
      triggerSource = metadata.triggerSource
    )
  }

  val legacyEmailGraph =
    ComposerGraph.build(legacyOrchestratedEmailInput, composedEmailEventProducer, failedEventProducer) {
      (orchestratedEmail: OrchestratedEmailV3) =>
        emailComposer(orchestratedEmail)
    }

  val aivenEmailGraph =
    ComposerGraph.build(aivenOrchestratedEmailInput, composedEmailEventProducer, failedEventProducer) {
      (orchestratedEmail: OrchestratedEmailV3) =>
        emailComposer(orchestratedEmail)
    }

  val legacySmsGraph = ComposerGraph.build(legacyOrchestratedSMSInput, composedSMSEventProducer, failedEventProducer) {
    (orchestratedSMS: OrchestratedSMSV2) =>
      smsComposer(orchestratedSMS)
  }

  val aivenSmsGraph = ComposerGraph.build(aivenOrchestratedSMSInput, composedSMSEventProducer, failedEventProducer) {
    (orchestratedSMS: OrchestratedSMSV2) =>
      smsComposer(orchestratedSMS)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info("Creating graphs")

  Seq(
    (legacyEmailGraph, "Legacy email"),
    (aivenEmailGraph, "Aiven email"),
    (legacySmsGraph, "Legacy SMS"),
    (aivenSmsGraph, "Aiven SMS")
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

  log.info("Composer now running")
}
