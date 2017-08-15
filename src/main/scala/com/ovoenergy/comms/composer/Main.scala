package com.ovoenergy.comms.composer

import java.time.OffsetDateTime
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import com.ovoenergy.comms.composer.aws.TemplateContextFactory
import com.ovoenergy.comms.composer.email.EmailComposer
import com.ovoenergy.comms.composer.kafka.ComposerGraph
import com.ovoenergy.comms.model.email.OrchestratedEmailV3
import com.ovoenergy.comms.model.sms.OrchestratedSMSV2
import com.ovoenergy.comms.model.{Customer, Metadata, MetadataV2}
import com.ovoenergy.comms.composer.sms.SMSComposer
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import cats.instances.either._
import com.ovoenergy.comms.helpers.Kafka
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

  implicit val config = ConfigFactory.load()

  val templateContext = TemplateContextFactory(runningInDockerCompose, config.getString("aws.region"))

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val scheduler = actorSystem.scheduler

  val composedEmailEventProducer = Kafka.aiven.composedEmail.v2.retryPublisher
  val composedSMSEventProducer = Kafka.aiven.composedSms.v2.retryPublisher

  val failedEventProducer = Kafka.aiven.failed.v2.retryPublisher

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

  val emailGraph =
    ComposerGraph.build(Kafka.aiven.orchestratedEmail.v3, composedEmailEventProducer, failedEventProducer) {
      (orchestratedEmail: OrchestratedEmailV3) =>
        emailComposer(orchestratedEmail)
    }

  val smsGraph = ComposerGraph.build(Kafka.aiven.orchestratedSMS.v2, composedSMSEventProducer, failedEventProducer) {
    (orchestratedSMS: OrchestratedSMSV2) =>
      smsComposer(orchestratedSMS)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info("Creating graphs")

  Seq(
    (emailGraph, "Email Composition"),
    (smsGraph, "SMS Composition")
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
