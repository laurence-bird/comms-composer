package com.ovoenergy.comms.composer

import http.{RenderRestApi, AdminRestApi}
import kafka.KafkaStream
import logic.{Print, Email, Sms}
import rendering.{HandlebarsWrapper, HandlebarsRendering, Rendering}
import cats.effect.IO
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.ovoenergy.comms.templates.TemplatesContext
import fs2._
import org.http4s.server.blaze.BlazeBuilder
import com.ovoenergy.comms.model._

import scala.concurrent.ExecutionContext.Implicits.global

object Composer extends StreamApp[IO] with Logging {
  override def stream(
      args: List[String],
      requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] = {

    Stream
      .eval(Config.load[IO])
      .flatMap { config =>
        implicit val hash: Hash[IO] = Hash[IO]
        implicit val time: Time[IO] = Time[IO]
        implicit val rendering: Rendering[IO] =
          Rendering[IO](HandlebarsRendering(HandlebarsWrapper.apply))

        val topics = config.kafka.topics
        val kafkaStream: KafkaStream[IO] = KafkaStream[IO](config.kafka, hash, time)

        Store.stream[IO](config.store).flatMap { implicit store =>
          // TODO We need to get rid of this ASAP it creates an s3client and does not close it
          implicit val templatesContext: TemplatesContext = TemplatesContext
            .cachingContext(new DefaultAWSCredentialsProviderChain, config.templates.bucket.name)

          implicit val emailTemplates: Templates[IO, Templates.Email] = Templates.email[IO]
          implicit val smsTemplates: Templates[IO, Templates.Sms] = Templates.sms[IO]
          implicit val printTemplates: Templates[IO, Templates.Print] = Templates.print[IO]

          val http =
            BlazeBuilder[IO]
              .mountService(AdminRestApi[IO].adminService, "admin")
              .mountService(RenderRestApi[IO](Print.http[IO]).renderService, "render")
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
