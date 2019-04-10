package com.ovoenergy.comms.composer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.concurrent._

import cats.effect.{ExitCode, IO, IOApp}
import cats.Id
import cats.implicits._

import org.http4s.Uri
import org.http4s.implicits._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.Client
import org.http4s.client.middleware.{Logger => RequestLogger}
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.headers.{`User-Agent`, AgentProduct}

import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3._
import com.amazonaws.services.dynamodbv2._

import fs2._

import com.ovoenergy.comms.aws.common.model.Region
import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.model.sms.{ComposedSMSV4, OrchestratedSMSV3}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates._
import com.ovoenergy.comms.deduplication.ProcessingStore
import com.ovoenergy.comms.composer.kafka.Kafka

import cache._
import s3._
import retriever._
import parsing.handlebars._

// This is to import avro custom format. Intellij does not spot it because they are used by the macro
import com.ovoenergy.comms.model._

import http.{AdminRestApi, RenderRestApi}
import logic.{Email, Print, Sms}
import rendering.{HandlebarsRendering, HandlebarsWrapper, PdfRendering, Rendering}

import ShowInstances._
object Main extends IOApp {

  def mainExecutionContextStream: Stream[IO, ExecutionContext] = {
    Stream
      .bracket(IO(new ForkJoinPool(16)))(ex => IO(ex.shutdown()))
      .map(ExecutionContext.fromExecutorService)
  }

  def httpClientStream(implicit ec: ExecutionContext): Stream[IO, Client[IO]] = {
    BlazeClientBuilder[IO](ec)
      .withUserAgent(
        `User-Agent`(AgentProduct(s"ovo-energy/comms/${BuildInfo.name}", Some(BuildInfo.version))))
      .withResponseHeaderTimeout(60.seconds)
      .withMaxTotalConnections(256)
      .withMaxConnectionsPerRequestKey(_ => 256)
      .stream
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
      mainEc: ExecutionContext,
      httpClient: Client[IO],
      amazonS3: AmazonS3Client,
      logger: SelfAwareStructuredLogger[IO],
      deduplication: ProcessingStore[IO, String]) = {
    val loggingHttpClient: Client[IO] = RequestLogger[IO](false, false)(httpClient)

    implicit val ec = mainEc
    implicit val hash: Hash[IO] = Hash[IO]
    implicit val time: Time[IO] = Time[IO]

    implicit val rendering: Rendering[IO] =
      Rendering[IO](
        HandlebarsRendering(HandlebarsWrapper.apply),
        PdfRendering[IO](loggingHttpClient, config.docRaptor))

    implicit val store: Store[IO] =
      Store.fromHttpClient(loggingHttpClient, config.store, new Store.RandomSuffixKeys)

    implicit val templatesContext: TemplatesContext = {
      val s3Client = new AmazonS3ClientWrapper(amazonS3, config.templates.bucket.name)
      TemplatesContext(
        templatesRetriever = new TemplatesS3Retriever(s3Client),
        parser = new HandlebarsParsing(new PartialsS3Retriever(s3Client)),
        cachingStrategy = CachingStrategy
          .caffeine[TemplateManifest, ErrorsOr[CommTemplate[Id]]](maximumSize = 100)
      )
    }

    implicit val emailTemplates: Templates[IO, Templates.Email] = Templates.email[IO]
    implicit val smsTemplates: Templates[IO, Templates.Sms] = Templates.sms[IO]
    implicit val printTemplates: Templates[IO, Templates.Print] = Templates.print[IO]

    val routes =
      Router[IO](
        "/render" -> RenderRestApi[IO](Print.http[IO]).renderService,
        "/admin" -> AdminRestApi[IO].adminService
      ).orNotFound

    val http: Stream[IO, ExitCode] =
      BlazeServerBuilder[IO]
        .withExecutionContext(mainEc)
        .bindHttp(config.http.port, config.http.host)
        .withHttpApp(routes)
        .serve

    val topics = config.kafka.topics
    val kafka = Kafka(config.kafka, time, deduplication, logger)

    val email: Stream[IO, Unit] = kafka.stream[OrchestratedEmailV4, ComposedEmailV4](
      topics.orchestratedEmail,
      topics.composedEmail,
      Email[IO](_)
    )
    val sms = kafka.stream[OrchestratedSMSV3, ComposedSMSV4](
      topics.orchestratedSms,
      topics.composedSms,
      Sms[IO](_)
    )
    val print = kafka.stream[OrchestratedPrintV2, ComposedPrintV2](
      topics.orchestratedPrint,
      topics.composedPrint,
      Print[IO](_)
    )

    Stream(email.drain, sms.drain, print.drain, http).parJoinUnbounded
  }

  override def run(args: List[String]): IO[ExitCode] = {

    implicit val ec = ExecutionContext.global

    val stream: Stream[IO, ExitCode] = for {
      config <- Stream.eval(Config.load[IO])
      mainEc <- mainExecutionContextStream
      httpClient <- httpClientStream(mainEc)
      amazonS3 <- s3ClientStream(config.store.s3Endpoint, config.store.region)
      logger <- Stream.eval(Slf4jLogger.create[IO])
      dynamoDb <- dynamoDbStream(config.deduplicationDynamoDbEndpoint, config.store.region)
      deduplication = ProcessingStore[IO, String, String](config.deduplication, dynamoDb)
      _ <- Stream.eval(logger.info(s"Config: ${config}"))
      result <- buildStream(config, mainEc, httpClient, amazonS3, logger, deduplication)
    } yield result

    stream.compile.drain.as(ExitCode.Success)
  }
}
