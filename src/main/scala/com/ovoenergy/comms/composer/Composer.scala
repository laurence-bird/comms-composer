package com.ovoenergy.comms.composer

import com.amazonaws.{ClientConfiguration, Protocol}
import http.{RenderRestApi, AdminRestApi}
import kafka.KafkaStream
import logic.{Print, Email, Sms}
import rendering.{HandlebarsWrapper, HandlebarsRendering, Rendering, PdfRendering}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.comms.aws.common.model.Region
import com.ovoenergy.comms.templates._
import com.ovoenergy.comms.templates.cache._
import com.ovoenergy.comms.templates.s3._
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates.retriever._
import com.ovoenergy.comms.templates.parsing.handlebars._

// This is to import avro custom format. Intellij does not spot it because they are used by the macro
import com.ovoenergy.comms.model._

import cats.effect.IO
import cats.Id

import fs2._

import org.http4s.Uri
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.client.blaze.Http1Client

import scala.concurrent.ExecutionContext.Implicits.global

object Composer extends StreamApp[IO] with Logging {

  override def stream(
      args: List[String],
      requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] = {

    def s3ClientStream(endpoint: Option[Uri], region: Region): Stream[IO, AmazonS3Client] = {

      val buildClient = IO {
        val builder = AmazonS3Client
          .builder()

        endpoint.foreach { uri =>
          builder
            .withEndpointConfiguration(new EndpointConfiguration(uri.renderString, region.value))
            .withPathStyleAccessEnabled(true)
            // TODO Setup this only if the endpoint is http
            .withClientConfiguration(new ClientConfiguration().withProtocol(Protocol.HTTP))
        }

        builder.build()
      }

      Stream.bracket(buildClient)(
        c => Stream.emit(c.asInstanceOf[AmazonS3Client]),
        c => IO(c.shutdown()))
    }

    Stream
      .eval(Config.load[IO])
      .flatMap { config =>
        Scheduler[IO](corePoolSize = 2).flatMap { implicit sch =>
          Http1Client.stream[IO]().flatMap { httpClient =>
            s3ClientStream(config.store.s3Endpoint, config.store.region).flatMap { amazonS3 =>
              implicit val hash: Hash[IO] = Hash[IO]
              implicit val time: Time[IO] = Time[IO]
              implicit val rendering: Rendering[IO] =
                Rendering[IO](
                  HandlebarsRendering(HandlebarsWrapper.apply),
                  PdfRendering[IO](httpClient, config.docRaptor))

              val topics = config.kafka.topics
              val kafkaStream: KafkaStream[IO] = KafkaStream(config.kafka, hash, time)

              implicit val store: Store[IO] =
                Store.fromHttpClient(httpClient, config.store, new Store.RandomSuffixKeys)

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

              val http =
                BlazeBuilder[IO]
                  .bindHttp(config.http.port, config.http.host)
                  .mountService(AdminRestApi[IO].adminService, "/admin")
                  .mountService(RenderRestApi[IO](Print.http[IO]).renderService, "/render")
                  .serve

              val email = kafkaStream(topics.orchestratedEmail, topics.composedEmail)(Email[IO](_))
              val sms = kafkaStream(topics.orchestratedSms, topics.composedSms)(Sms[IO](_))
              val print = kafkaStream(topics.orchestratedPrint, topics.composedPrint)(Print[IO](_))

              Stream(
                http,
                email.drain.covaryOutput[StreamApp.ExitCode],
                sms.drain.covaryOutput[StreamApp.ExitCode],
                print.drain.covaryOutput[StreamApp.ExitCode]
              ).joinUnbounded ++ Stream.emit(StreamApp.ExitCode.Error)

            }

          }
        }
      }

  }

}