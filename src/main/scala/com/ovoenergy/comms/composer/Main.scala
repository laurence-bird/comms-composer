package com.ovoenergy.comms.composer

import java.nio.file.Paths

import akka.actor.ActorSystem
import cats.~>
import cats.effect.IO
import cats.effect.Effect
import cats.instances.either._
import com.amazonaws.regions.Regions
import com.ovoenergy.comms.composer.aws.{AwsClientProvider, TemplateContextFactory}
import com.ovoenergy.comms.composer.email.{EmailComposer, EmailComposerA, EmailInterpreter}
import com.ovoenergy.comms.composer.http.{AdminRestApi, HttpClient, HttpServerConfig, RenderRestApi}
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.kafka.EventProcessor
import com.ovoenergy.comms.composer.print.{PrintComposer, PrintComposerA, PrintInterpreter, RenderedPrintPdf}
import com.ovoenergy.comms.composer.print.PrintInterpreter.PrintContext
import com.ovoenergy.comms.composer.rendering.pdf.DocRaptorConfig
import com.ovoenergy.comms.composer.repo.S3PdfRepo.S3Config
import com.ovoenergy.comms.composer.sms.{SMSComposer, SMSComposerA, SMSInterpreter}
import com.ovoenergy.comms.helpers.{Kafka, KafkaClusterConfig, Topic}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.{ComposedEmailV3, OrchestratedEmailV3}
import com.ovoenergy.comms.model.print.{ComposedPrint, OrchestratedPrint}
import com.ovoenergy.comms.model.sms.{ComposedSMSV3, OrchestratedSMSV2}
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Retry
import com.ovoenergy.fs2.kafka.{ConsumerSettings, Subscription, consumeProcessAndCommit}
import com.ovoenergy.kafka.serialization.core.{constDeserializer}
import com.sksamuel.avro4s.{FromRecord, SchemaFor, ToRecord}
import com.typesafe.config.{Config, ConfigFactory}
import fs2._

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.config.SslConfigs
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.Server

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, reflectiveCalls}
import scala.language.reflectiveCalls
import scala.reflect.ClassTag

object Main extends StreamApp[IO] with AdminRestApi with Logging with RenderRestApi {

  val runningInDockerCompose = sys.env.get("DOCKER_COMPOSE").contains("true")

  if (runningInDockerCompose) {
    // accept the self-signed certs from the SSL proxy sitting in front of the fake S3 container
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
  }

  implicit def consumerRecordLoggable[K, V]: Loggable[ConsumerRecord[K, V]] = new Loggable[ConsumerRecord[K, V]] {
    override def mdcMap(a: ConsumerRecord[K, V]): Map[String, String] = Map(
      "kafkaTopic" -> a.topic(),
      "kafkaPartition" -> a.partition().toString,
      "kafkaOffset" -> a.offset().toString
    )
  }

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

  val smsInterpreter: ~>[SMSComposerA, FailedOr] = SMSInterpreter(templateContext)
  val smsComposer = (orchestratedSMS: OrchestratedSMSV2) =>
    SMSComposer.program(orchestratedSMS).foldMap(smsInterpreter)

  val printInterpreter: ~>[PrintComposerA, FailedOr] = PrintInterpreter(printContext)
  val printComposer = (orchestratedPrint: OrchestratedPrint) =>
    PrintComposer.program(orchestratedPrint).foldMap(printInterpreter)

  def renderPrint(commManifest: CommManifest, data: Map[String, TemplateData]): IO[FailedOr[RenderedPrintPdf]] = {
    IO(PrintComposer.httpProgram(commManifest, data).foldMap(printInterpreter))
  }

  log.info(s"Starting HTTP server on host=${httpServerConfig.host} port=${httpServerConfig.port}")

  val httpServer: IO[Server[IO]] = BlazeBuilder[IO]
    .bindHttp(httpServerConfig.port, httpServerConfig.host)
    .mountService(adminService, "/")
    .mountService(renderService(renderPrint), "/")
    .start

  val aivenCluster = Kafka.aiven
  val kafkaClusterConfig: KafkaClusterConfig = aivenCluster.kafkaConfig

  val pollTimeout: FiniteDuration = 150.milliseconds

  val consumerNativeSettings: Map[String, AnyRef] = {
    Map(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaClusterConfig.hosts,
      ConsumerConfig.GROUP_ID_CONFIG -> kafkaClusterConfig.groupId
    ) ++ kafkaClusterConfig.ssl
      .map { ssl =>
        Map(
          CommonClientConfigs.SECURITY_PROTOCOL_CONFIG -> "SSL",
          SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG -> Paths.get(ssl.keystore.location).toAbsolutePath.toString,
          SslConfigs.SSL_KEYSTORE_TYPE_CONFIG -> "PKCS12",
          SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG -> ssl.keystore.password,
          SslConfigs.SSL_KEY_PASSWORD_CONFIG -> ssl.keyPassword,
          SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG -> Paths.get(ssl.truststore.location).toString,
          SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG -> "JKS",
          SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG -> ssl.truststore.password
        )
      }
      .getOrElse(Map.empty) ++ kafkaClusterConfig.nativeProperties

  }

  val consumerSettings: ConsumerSettings = ConsumerSettings(
    pollTimeout = pollTimeout,
    maxParallelism = Int.MaxValue,
    nativeSettings = consumerNativeSettings
  )

  type Record[T] = ConsumerRecord[Unit, Option[T]]

  def processEvent[F[_], T: SchemaFor: ToRecord: FromRecord: ClassTag, A](
      f: Record[T] => F[A],
      topic: Topic[T])(implicit F: Effect[F], config: Config, ec: ExecutionContext): fs2.Stream[F, A] = {

    val valueDeserializer = topic.deserializer.right.get

    consumeProcessAndCommit[F].apply(
      Subscription.topics(topic.name),
      constDeserializer[Unit](()),
      valueDeserializer,
      consumerSettings
    )(f)
  }

  def emailProcessor =
    EventProcessor[IO, OrchestratedEmailV3, ComposedEmailV3](composedEmailEventProducer,
                                                             failedEventProducer,
                                                             emailComposer)
  def smsProcessor =
    EventProcessor[IO, OrchestratedSMSV2, ComposedSMSV3](composedSMSEventProducer, failedEventProducer, smsComposer)
  def printProcessor =
    EventProcessor[IO, OrchestratedPrint, ComposedPrint](composedPrintEventProducer,
                                                         failedEventProducer,
                                                         printComposer)

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] = {

    val emailStream: Stream[IO, Unit] =
      processEvent[IO, OrchestratedEmailV3, Unit](emailProcessor, aivenCluster.orchestratedEmail.v3)

    val smsStream: Stream[IO, Unit] =
      processEvent[IO, OrchestratedSMSV2, Unit](smsProcessor, aivenCluster.orchestratedSMS.v2)

    val printStream: Stream[IO, Unit] =
      processEvent[IO, OrchestratedPrint, Unit](printProcessor, aivenCluster.orchestratedPrint.v1)

    val httpServerStream =
      Stream.bracket[IO, Server[IO], Server[IO]](httpServer)(server => Stream.emit(server), server => server.shutdown)

    httpServerStream.flatMap { server =>
      emailStream
        .mergeHaltBoth(smsStream)
        .mergeHaltBoth(printStream)
        .drain
        .covaryOutput[StreamApp.ExitCode] ++ Stream.emit(StreamApp.ExitCode.Error)
    }
  }

  log.info("Composer now running")
}
