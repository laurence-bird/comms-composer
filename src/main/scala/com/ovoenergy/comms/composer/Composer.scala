package com.ovoenergy.comms.composer

import cats.effect.{ExitCode, IO, IOApp}
import cats.Id
import cats.implicits._
import com.amazonaws.{ClientConfiguration, Protocol}
import http.{AdminRestApi, RenderRestApi}
import kafka.Kafka
import logic.{Email, Print, Sms}
import rendering.{HandlebarsRendering, HandlebarsWrapper, PdfRendering, Rendering}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.comms.aws.common.model.Region
import com.ovoenergy.comms.model.email.{ComposedEmailV4, OrchestratedEmailV4}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.model.sms.{ComposedSMSV4, OrchestratedSMSV3}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates._
import cache._
import org.http4s.blaze.channel.{ChannelOptions, DefaultPoolSize}
import s3._
import retriever._
import parsing.handlebars._
import org.http4s.implicits._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.server.{DefaultServiceErrorHandler, Router, defaults}
import org.http4s.server.blaze.BlazeServerBuilder

// This is to import avro custom format. Intellij does not spot it because they are used by the macro
import com.ovoenergy.comms.model._

import fs2._

import org.http4s.Uri
import org.http4s.headers.{`User-Agent`, AgentProduct}
import org.http4s.client.Client
import org.http4s.client.middleware.{Logger => RequestLogger}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.concurrent._

object Composer extends IOApp {

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

  override def run(args: List[String]): IO[ExitCode] = {

    val stream: Stream[IO, Stream[IO, Any]] = for {
      config <- Stream.eval(Config.load[IO])
      mainEc <- mainExecutionContextStream
      httpClient <- httpClientStream(mainEc)
      amazonS3 <- s3ClientStream(config.store.s3Endpoint, config.store.region)
      logger <- Stream.eval(Slf4jLogger.create[IO])
    } yield {

      val loggingHttpClient: Client[IO] = RequestLogger[IO](true, true)(httpClient)

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

      val http =
        BlazeServerBuilder[IO]
          .withExecutionContext(mainEc)
          .bindHttp(config.http.port, config.http.host)
          .withHttpApp(routes)
          .withHttpApp(AdminRestApi[IO].adminService.orNotFound)
          .serve

      val topics = config.kafka.topics
      val kafka = Kafka(config.kafka, time, logger)

      val email = kafka.stream[OrchestratedEmailV4, ComposedEmailV4](
        topics.orchestratedEmail,
        topics.composedEmail,
        Email[IO](_))
      val sms = kafka.stream[OrchestratedSMSV3, ComposedSMSV4](
        topics.orchestratedSms,
        topics.composedSms,
        Sms[IO](_))
      val print = kafka.stream[OrchestratedPrintV2, ComposedPrintV2](
        topics.orchestratedPrint,
        topics.composedPrint,
        Print[IO](_))

      Stream(email, sms, print, http).parJoinUnbounded
    }

    stream.flatten.compile.drain.as(ExitCode.Success)
  }
}
