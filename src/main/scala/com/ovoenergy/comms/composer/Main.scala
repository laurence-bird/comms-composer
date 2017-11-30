package com.ovoenergy.comms.composer

import java.time.OffsetDateTime

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.scaladsl.{RunnableGraph, Sink, Source}
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import com.ovoenergy.comms.composer.aws.{AwsClientProvider, TemplateContextFactory}
import com.ovoenergy.comms.composer.email.{EmailComposer, EmailComposerA, EmailInterpreter}
import com.ovoenergy.comms.composer.kafka.ComposerGraph
import com.ovoenergy.comms.model.email.{ComposedEmailV2, OrchestratedEmailV3}
import com.ovoenergy.comms.model.sms.{ComposedSMSV2, OrchestratedSMSV2}
import com.ovoenergy.comms.model.{Customer, FailedV2, Metadata, MetadataV2}
import com.ovoenergy.comms.composer.sms.{SMSComposer, SMSComposerA, SMSInterpreter}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import cats.instances.either._
import cats.~>
import com.amazonaws.regions.Regions
import com.ovoenergy.comms.composer.Interpreters.FailedOr
import com.ovoenergy.comms.composer.http.{AdminRestApi, HttpClient, HttpServerConfig}
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.print.PrintInterpreter.PrintContext
import com.ovoenergy.comms.composer.print.{PrintComposer, PrintComposerA, PrintInterpreter}
import com.ovoenergy.comms.composer.rendering.pdf.DocRaptorConfig
import com.ovoenergy.comms.composer.repo.S3PdfRepo.S3Config
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model.print.{ComposedPrint, OrchestratedPrint}
import com.ovoenergy.comms.serialisation.Retry
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder

import scala.concurrent.Future
import scala.concurrent.duration._
// Implicits
import scala.language.reflectiveCalls
import com.ovoenergy.comms.serialisation.Codecs._
import io.circe.generic.auto._
import scala.concurrent.duration.FiniteDuration

object Main extends App with AdminRestApi {

  val runningInDockerCompose = sys.env.get("DOCKER_COMPOSE").contains("true")

  if (runningInDockerCompose) {
    // accept the self-signed certs from the SSL proxy sitting in front of the fake S3 container
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
  }

  val log = LoggerFactory.getLogger(getClass)

  implicit val config = ConfigFactory.load()

  val region = config.getString("aws.region")
  val bucketName = config.getString("aws.s3.print-pdf.bucket-name")
  val s3Client = AwsClientProvider.genClients(runningInDockerCompose, Regions.fromName(region))

  val s3Config = S3Config(s3Client, bucketName)
  val templateContext = TemplateContextFactory(runningInDockerCompose, region)

  val retryConfig = RetryConfig(5, Retry.Backoff.constantDelay(1.second))
  val docRaptorApiKey = config.getString("doc-raptor.apiKey")
  val docRaptorUrl = config.getString("doc-raptor.url")
  val test = config.getBoolean("doc-raptor.test")
  val httpServerConfig = HttpServerConfig.unsafeFromConfig(config.getConfig("http-server"))

  val printContext = PrintContext(
    docRaptorConfig = DocRaptorConfig(docRaptorApiKey, docRaptorUrl, test, retryConfig),
    s3Config = s3Config,
    retryConfig = retryConfig,
    templateContext = templateContext,
    httpClient = HttpClient.apply
  )

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val scheduler = actorSystem.scheduler

  def exitOnFailure[T](either: Either[Retry.Failed, T], errorMessage: String): T = either match {
    case Left(l) => {
      log.error(s"Failed to register $errorMessage schema. Made ${l.attemptsMade} attempts", l.finalException)
      sys.exit(1)
    }
    case Right(r) => r
  }

  val composedEmailEventProducer =
    exitOnFailure(Kafka.aiven.composedEmail.v3.retryPublisher, "composed email")
  val composedSMSEventProducer =
    exitOnFailure(Kafka.aiven.composedSms.v3.retryPublisher, "composed sms")
  val composedPrintEventProducer =
    exitOnFailure(Kafka.aiven.composedPrint.v1.retryPublisher, "composed print")

  val failedEventProducer = exitOnFailure(Kafka.aiven.failed.v2.retryPublisher, "failed")

  val emailInterpreter: ~>[EmailComposerA, FailedOr] = EmailInterpreter(templateContext)
  val emailComposer = (orchestratedEmail: OrchestratedEmailV3) =>
    EmailComposer.program(orchestratedEmail).foldMap(emailInterpreter)
  val emailGraph =
    ComposerGraph.build(Kafka.aiven.orchestratedEmail.v3, composedEmailEventProducer, failedEventProducer)(
      (orchestratedEmail: OrchestratedEmailV3) => emailComposer(orchestratedEmail))

  val smsInterpreter: ~>[SMSComposerA, FailedOr] = SMSInterpreter(templateContext)
  val smsComposer = (orchestratedSMS: OrchestratedSMSV2) =>
    SMSComposer.program(orchestratedSMS).foldMap(smsInterpreter)
  val smsGraph = ComposerGraph.build(Kafka.aiven.orchestratedSMS.v2, composedSMSEventProducer, failedEventProducer)(
    (orchestratedSMS: OrchestratedSMSV2) => smsComposer(orchestratedSMS))

  val printInterpreter: ~>[PrintComposerA, FailedOr] = PrintInterpreter(printContext)
  val printComposer = (orchestratedPrint: OrchestratedPrint) =>
    PrintComposer.program(orchestratedPrint).foldMap(printInterpreter)
  val printGraph =
    ComposerGraph.build(Kafka.aiven.orchestratedPrint.v1, composedPrintEventProducer, failedEventProducer)(
      (orchestratedPrint: OrchestratedPrint) => printComposer(orchestratedPrint))

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info(s"Starting HTTP server on host=${httpServerConfig.host} port=${httpServerConfig.port}")

  val httpServer = BlazeBuilder
    .bindHttp(httpServerConfig.port, httpServerConfig.host)
    .mountService(adminService, "/")
    .start
    .unsafeRun()

  log.info("Creating graphs")

  Seq(
    (emailGraph, "Email Composition"),
    (printGraph, "Print Composition"),
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
