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
import com.ovoenergy.comms.composer.kafka.{EventProcessor, Producer}
import com.ovoenergy.comms.composer.print.{PrintComposer, PrintComposerA, PrintInterpreter, RenderedPrintPdf}
import com.ovoenergy.comms.composer.print.PrintInterpreter.PrintContext
import com.ovoenergy.comms.composer.rendering.pdf.DocRaptorConfig
import com.ovoenergy.comms.composer.repo.S3PdfRepo.S3Config
import com.ovoenergy.comms.composer.sms.{SMSComposer, SMSComposerA, SMSInterpreter}
import com.ovoenergy.comms.helpers.{Kafka, KafkaClusterConfig, Topic}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.{ComposedEmailV3, ComposedEmailV4, OrchestratedEmailV3, OrchestratedEmailV4}
import com.ovoenergy.comms.model.print.{ComposedPrint, ComposedPrintV2, OrchestratedPrint, OrchestratedPrintV2}
import com.ovoenergy.comms.model.sms.{ComposedSMSV3, ComposedSMSV4, OrchestratedSMSV2, OrchestratedSMSV3}
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Retry
import com.ovoenergy.fs2.kafka.{ConsumerSettings, Subscription, consumeProcessAndCommit}
import com.ovoenergy.kafka.serialization.core.constDeserializer
import com.sksamuel.avro4s.{FromRecord, SchemaFor, ToRecord}
import com.typesafe.config.{Config, ConfigFactory}
import fs2._
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.{KafkaProducer, RecordMetadata}
import org.apache.kafka.common.config.SslConfigs
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.Server

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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

  implicit def recordLoggable = new Loggable[RecordMetadata] {
    override def mdcMap(rm: RecordMetadata): Map[String, String] = {
      Map(
        "kafkaTopic" -> rm.topic(),
        "kafkaPartition" -> rm.partition().toString,
        "kafkaOffset" -> rm.offset().toString
      )
    }
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

  val composedEmailEventProducer = publisherFor[ComposedEmailV4](Kafka.aiven.composedEmail.v4, _.metadata.eventId)
  val composedSMSEventProducer = publisherFor[ComposedSMSV4](Kafka.aiven.composedSms.v4, _.metadata.eventId)
  val composedPrintEventProducer = publisherFor[ComposedPrintV2](Kafka.aiven.composedPrint.v2, _.metadata.eventId)

  val failedEventProducer = publisherFor[FailedV3](Kafka.aiven.failed.v3, _.metadata.eventId)

  val emailInterpreter: ~>[EmailComposerA, FailedOr] = EmailInterpreter(templateContext)
  val emailComposer = (orchestratedEmail: OrchestratedEmailV4) =>
    EmailComposer.program(orchestratedEmail).foldMap(emailInterpreter)

  val smsInterpreter: ~>[SMSComposerA, FailedOr] = SMSInterpreter(templateContext)
  val smsComposer = (orchestratedSMS: OrchestratedSMSV3) =>
    SMSComposer.program(orchestratedSMS).foldMap(smsInterpreter)

  val printInterpreter: ~>[PrintComposerA, FailedOr] = PrintInterpreter(printContext)
  val printComposer = (orchestratedPrint: OrchestratedPrintV2) =>
    PrintComposer.program(orchestratedPrint).foldMap(printInterpreter)

  def renderPrint(templateManifest: TemplateManifest,
                  data: Map[String, TemplateData]): IO[FailedOr[RenderedPrintPdf]] = {
    IO(PrintComposer.httpProgram(templateManifest, data).foldMap(printInterpreter))
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

  /*
      Temporary fix while we investigate issues with print consumer
   */
  val printNativeSettings: Map[String, AnyRef] = {
    consumerNativeSettings ++ Map(ConsumerConfig.MAX_POLL_RECORDS_CONFIG -> "1")
  }

  val printConsumerSettings = ConsumerSettings(
    pollTimeout = pollTimeout,
    maxParallelism = Int.MaxValue,
    nativeSettings = printNativeSettings
  )

  type Record[T] = ConsumerRecord[Unit, Option[T]]

  def processEvent[F[_], T: SchemaFor: ToRecord: FromRecord: ClassTag, A](
      f: Record[T] => F[A],
      topic: Topic[T],
      settings: ConsumerSettings)(implicit F: Effect[F], config: Config, ec: ExecutionContext): fs2.Stream[F, A] = {

    val valueDeserializer = topic.deserializer.right.get

    consumeProcessAndCommit[F].apply(
      Subscription.topics(topic.name),
      constDeserializer[Unit](()),
      valueDeserializer,
      settings
    )(f)
  }

  def emailProcessor =
    EventProcessor[IO, OrchestratedEmailV4, ComposedEmailV4](composedEmailEventProducer,
                                                             failedEventProducer,
                                                             emailComposer)
  def smsProcessor =
    EventProcessor[IO, OrchestratedSMSV3, ComposedSMSV4](composedSMSEventProducer, failedEventProducer, smsComposer)
  def printProcessor =
    EventProcessor[IO, OrchestratedPrintV2, ComposedPrintV2](composedPrintEventProducer,
                                                             failedEventProducer,
                                                             printComposer)

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] = {

    val emailStream: Stream[IO, Unit] =
      processEvent[IO, OrchestratedEmailV4, Unit](emailProcessor, aivenCluster.orchestratedEmail.v4, consumerSettings)

    val smsStream: Stream[IO, Unit] =
      processEvent[IO, OrchestratedSMSV3, Unit](smsProcessor, aivenCluster.orchestratedSMS.v3, consumerSettings)

    val printStream: Stream[IO, Unit] =
      processEvent[IO, OrchestratedPrintV2, Unit](printProcessor, aivenCluster.orchestratedPrint.v2, consumerSettings)

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

  def publisherFor[E: Loggable](topic: Topic[E], key: E => String)(implicit schemaFor: SchemaFor[E],
                                                                   toRecord: ToRecord[E],
                                                                   classTag: ClassTag[E]): E => IO[RecordMetadata] = {
    import cats.syntax.flatMap._
    val producer: KafkaProducer[String, E] = exitOnFailure(Producer[E](topic), topic.name)
    val publisher: E => IO[RecordMetadata] = { e: E =>
      info(e)(s"Sending event to topic ${topic.name} \n")

      Producer.publisher[E, IO](key, producer, topic.name)(e)
    }
    publisher.andThen(f => f.flatMap(rm => IO(info(rm)(s"Event sent to topic ${topic.name}")) >> IO.pure(rm)))
  }

  log.info("Composer now running")
}
