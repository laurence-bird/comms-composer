package com.ovoenergy.comms.composer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try
import java.util.concurrent._

import cats.effect.{ExitCode, IO, IOApp}
import cats.Id
import cats.implicits._

import org.http4s.{Uri, Request}
import org.http4s.implicits._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.Client
import org.http4s.client.middleware.{Logger => RequestLogger, Metrics => ClientMetrics}
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.headers.{`User-Agent`, AgentProduct}
import org.http4s.metrics.micrometer.{Config => Http4sMicrometerConfig, _}

import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.micrometer.core.instrument.{MeterRegistry, Tags}

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3._
import com.amazonaws.services.dynamodbv2._

import fs2._

import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.model.sms.{ComposedSMSV4, OrchestratedSMSV3}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates._
import com.ovoenergy.comms.deduplication.ProcessingStore
import com.ovoenergy.comms.composer.kafka.Kafka
import com.ovoenergy.comms.aws.s3.S3
import com.ovoenergy.comms.aws.common.CredentialsProvider
import com.ovoenergy.comms.aws.common.model.Region

import metrics._
import cache._
import s3._
import retriever._
import parsing.handlebars._

// This is to import avro custom format. The compiler does not spot it because they are used by the macro
import com.ovoenergy.comms.model._

import http._
import rendering._

import ShowInstances._
object Main extends IOApp {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def httpClientStream(
      meterRegistry: MeterRegistry,
      metricsConfig: Config.Metrics): Stream[IO, Client[IO]] = {
    BlazeClientBuilder[IO](ec)
      .withUserAgent(
        `User-Agent`(AgentProduct(s"ovo-energy/comms/${BuildInfo.name}", Some(BuildInfo.version))))
      .withResponseHeaderTimeout(10.seconds)
      .withMaxTotalConnections(256)
      .withMaxConnectionsPerRequestKey(_ => 256)
      .stream
      .flatMap { client =>
        val config: Http4sMicrometerConfig = Http4sMicrometerConfig(
          prefix = s"${metricsConfig.prefix}http4s.client.",
          tags =
            metricsConfig.tags.map { case (k, v) => Tags.of(k, v) }.foldLeft(Tags.empty)(_ and _)
        )

        val classifierFunc = { (request: Request[IO]) =>
          // We want to measure S3 with dedicated name and tags
          if (request.uri.host
              .map(_.value)
              .exists(x => x.contains("s3") && x.contains("amazonaws.com"))) {

            Try(new AmazonS3URI(request.uri.renderString)).toOption.map { s3Uri =>
              val bucket = s3Uri.getBucket
              // To have a uniform name between PRD and UAT
              val bucketNameTag: String = (if (bucket.contains("ovo-comms-rendered-content")) {
                                             "s3-bucket-name:ovo-comms-rendered-content".some
                                           } else {
                                             none[String]
                                           }).combineAll

              s"s3[$bucketNameTag]"
            }
          } else {
            None
          }
        }

        val meteredClient = Micrometer[IO](meterRegistry, config).map { micrometer =>
          ClientMetrics[IO](ops = micrometer, classifierF = classifierFunc)(client)
        }

        Stream.eval(meteredClient)
      }
  }

  def s3ClientStream(endpoint: Option[Uri], region: Region): Stream[IO, AmazonS3Client] = {

    val buildClient = IO {
      val builder = AmazonS3Client.builder()

      endpoint.foreach { uri =>
        builder
          .withEndpointConfiguration(new EndpointConfiguration(uri.renderString, region.value))
      }

      builder.build().asInstanceOf[AmazonS3Client]
    }

    Stream.bracket(buildClient)(c => IO(c.shutdown()))
  }

  def dynamoDbStream(endpoint: Option[Uri], region: Region): Stream[IO, AmazonDynamoDBAsync] = {
    val buildClient = IO {
      val builder = AmazonDynamoDBAsyncClientBuilder.standard()

      endpoint.foreach { uri =>
        builder
          .withEndpointConfiguration(new EndpointConfiguration(uri.renderString, region.value))
      }

      builder.build()
    }

    Stream.bracket(buildClient)(c => IO(c.shutdown()))
  }

  def buildStream(
      config: Config,
      httpClient: Client[IO],
      textRenderer: TextRenderer[IO],
      pdfRenderer: PdfRendering[IO],
      store: Store[IO],
      logger: SelfAwareStructuredLogger[IO],
      deduplication: ProcessingStore[IO, String],
      reporter: Reporter[IO]) = {

    implicit val implicitReporter: Reporter[IO] = reporter
    implicit val time: Time[IO] = Time[IO]

    val routes =
      Router[IO](
        "/admin" -> AdminRestApi[IO].adminService
      ).orNotFound

    val http: Stream[IO, ExitCode] =
      BlazeServerBuilder[IO]
        .bindHttp(config.http.port, config.http.host)
        .withHttpApp(routes)
        .serve

    val topics = config.kafka.topics
    val kafka = Kafka(config.kafka, time, deduplication, logger)

    val email: Stream[IO, Unit] = kafka.stream[OrchestratedEmailV4, ComposedEmailV4](
      topics.orchestratedEmail,
      topics.composedEmail,
      logic.Email[IO, IO.Par](store, textRenderer, time)(_)
    )

    val sms: Stream[IO, Unit] = kafka.stream[OrchestratedSMSV3, ComposedSMSV4](
      topics.orchestratedSms,
      topics.composedSms,
      logic.Sms[IO](store, textRenderer, time)(_)
    )

    val print: Stream[IO, Unit] = kafka.stream[OrchestratedPrintV2, ComposedPrintV2](
      topics.orchestratedPrint,
      topics.composedPrint,
      logic.Print[IO](store, textRenderer, pdfRenderer, time)(_)
    )

    Stream(email.drain, sms.drain, print.drain, http).parJoinUnbounded
  }

  override def run(args: List[String]): IO[ExitCode] = {

    implicit val ec = ExecutionContext.global

    import scala.reflect.internal.Reporter
    val stream: Stream[IO, ExitCode] = for {
      config <- Stream.eval(Config.load[IO])
      logger <- Stream.eval(Slf4jLogger.create[IO])
      dynamoDb <- dynamoDbStream(config.deduplicationDynamoDbEndpoint, config.store.region)
      deduplication = ProcessingStore[IO, String, String](config.deduplication, dynamoDb)
      meterRegistry <- Stream.resource(metrics.createMeterRegistry[IO](config.metrics))
      reporter = Reporter.fromRegistry[IO](meterRegistry, config.metrics)
      httpClient <- httpClientStream(meterRegistry, config.metrics)
      s3 = S3[IO](httpClient, CredentialsProvider.default[IO], config.store.region)
      store = Store[IO](s3, config.store, new Store.RandomSuffixKeys)
      textRendered <- Stream.eval(TextRenderer[IO](Templates[IO](s3, config.templates.bucket)))
      pdfRenderer = PdfRendering[IO](httpClient, config.docRaptor)
      _ <- Stream.eval(logger.info(s"Config: ${config}"))
      result <- buildStream(
        config,
        httpClient,
        textRendered,
        pdfRenderer,
        store,
        logger,
        deduplication,
        reporter)
    } yield result

    stream.compile.drain.as(ExitCode.Success)
  }
}
