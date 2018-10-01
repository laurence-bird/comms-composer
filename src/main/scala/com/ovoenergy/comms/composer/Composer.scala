package com.ovoenergy.comms.composer

import http.{RenderRestApi, AdminRestApi}
import kafka.KafkaStream
import logic.{Print, Email, Sms}
import rendering.{HandlebarsWrapper, HandlebarsRendering, Rendering, PdfRendering}

// This is to import avro custom format. Intellij does not spot it because they are used by the macro
import com.ovoenergy.comms.model._

import com.ovoenergy.comms.templates.TemplatesContext

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

import cats.effect.IO

import fs2._

import org.http4s.server.blaze.BlazeBuilder
import org.http4s.client.blaze.Http1Client

import scala.concurrent.ExecutionContext.Implicits.global

object Composer extends StreamApp[IO] with Logging {
  override def stream(
      args: List[String],
      requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] = {

    Stream
      .eval(Config.load[IO])
      .flatMap { config =>
        Scheduler[IO](corePoolSize = 2).flatMap { implicit sch =>
          Http1Client.stream[IO]().flatMap { httpClient =>
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

            // TODO We need to get rid of this ASAP it creates an s3client and does not close it
            implicit val templatesContext: TemplatesContext = TemplatesContext
              .cachingContext(new DefaultAWSCredentialsProviderChain, config.templates.bucket.name)
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
