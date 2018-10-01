package com.ovoenergy.comms.composer

import Config.Env.{Uat, Prd}
import http.HttpServerConfig
import rendering.PdfRendering.DocRaptorConfig
import kafka.KafkaStream.{Topics, KafkaConfig, Topic}
import com.ovoenergy.comms.model.{FailedV3, Feedback}
import com.ovoenergy.comms.model.email.{OrchestratedEmailV4, ComposedEmailV4}
import com.ovoenergy.comms.model.print.{ComposedPrintV2, OrchestratedPrintV2}
import com.ovoenergy.comms.model.sms.{OrchestratedSMSV3, ComposedSMSV4}
import com.ovoenergy.comms.aws.common.model.Region
import com.ovoenergy.comms.aws.s3.model.Bucket
import com.ovoenergy.fs2.kafka.{ConsumerSettings, ProducerSettings}
import com.ovoenergy.kafka.serialization.avro.SchemaRegistryClientSettings
import cats.implicits._
import cats.effect.Sync
import ciris._
import ciris.syntax._
import ciris.cats.effect._
import ciris.credstash.credstashF
import ciris.aiven.kafka.aivenKafkaSetup
import CirisAws._
import org.http4s.Uri
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig

import scala.concurrent.duration._
import scala.util.Try

case class TemplatesConfig(bucket: Bucket)

case class Config(
    http: HttpServerConfig,
    kafka: KafkaConfig,
    store: Store.Config,
    templates: TemplatesConfig,
    docRaptor: DocRaptorConfig
)

object Config {

  implicit val BucketConfigDecoder: ConfigDecoder[String, Bucket] =
    ConfigDecoder.catchNonFatal("AWS S3 Bucket") { str =>
      Bucket(str)
    }

  implicit val RegionConfigDecoder: ConfigDecoder[String, Region] =
    ConfigDecoder.catchNonFatal("AWS Region") { str =>
      Region(str)
    }

  implicit val UriConfigDecoder: ConfigDecoder[String, Uri] =
    ConfigDecoder.fromTry("Uri")(str => Try(Uri.unsafeFromString(str)))

  sealed trait Env {
    def toStringLowerCase: String = toString.toLowerCase
  }

  object Env {
    case object Uat extends Env
    case object Prd extends Env

    def fromString(str: String): Option[Env] = str.toUpperCase match {
      case "UAT" => Uat.some
      case "PRD" => Prd.some
      case _ => none[Env]
    }

    implicit val configDecoder: ConfigDecoder[String, Env] =
      ConfigDecoder.fromOption("ENV")(fromString)
  }

  def load[F[_]: Sync]: F[Config] = {

    val topics = Topics(
      orchestratedEmail = Topic[OrchestratedEmailV4]("comms.orchestrated.email.v4"),
      orchestratedSms = Topic[OrchestratedSMSV3]("comms.orchestrated.sms.v3"),
      orchestratedPrint = Topic[OrchestratedPrintV2]("comms.orchestrated.print.v2"),
      composedEmail = Topic[ComposedEmailV4]("comms.composed.email.v4"),
      composedSms = Topic[ComposedSMSV4]("comms.composed.sms.v4"),
      composedPrint = Topic[ComposedPrintV2]("comms.composed.print.v2"),
      failed = Topic[FailedV3]("comms.failed.v3"),
      feedback = Topic[Feedback]("comms.feedback")
    )

    val http = HttpServerConfig(
      "0.0.0.0",
      8080
    )

    withValue(envF[F, Option[Env]]("ENV")) {
      case Some(environment) =>
        loadConfig(
          awsF[F, Region](AwsRegion),
          envF[F, Bucket]("TEMPLATES_S3_BUCKET"),
          envF[F, Bucket]("RENDERED_S3_BUCKET"),
          aivenKafkaSetup[F](
            clientPrivateKey =
              credstashF()(s"${environment.toStringLowerCase}.kafka.client_private_key"),
            clientCertificate =
              credstashF()(s"${environment.toStringLowerCase}.kafka.client_certificate"),
            serviceCertificate =
              credstashF()(s"${environment.toStringLowerCase}.kafka.service_certificate")
          ),
          credstashF[F, Secret[String]]()(
            s"${environment.toStringLowerCase}.aiven.schema_registry.password"),
          credstashF[F, Secret[String]]()(s"${environment.toStringLowerCase}.docraptor.api_key")
        ) {
          (
              awsRegion,
              templatesBucket,
              storeBucket,
              kafkaSSL,
              schemaRegistryPassword,
              docRaptorApiKey) =>
            val docRaptor = DocRaptorConfig(
              docRaptorApiKey.value,
              Uri.uri("https://docraptor.com"),
              isTest = false
            )

            val store = Store.Config(storeBucket, awsRegion)

            val templates = TemplatesConfig(templatesBucket)

            val kafka = {

              val kafkaBootstrapServers = environment match {
                case Uat => "kafka-uat.ovo-uat.aivencloud.com:13581"
                case Prd => "kafka-prd.ovo-prd.aivencloud.com:21556"
              }

              val schemaRegistryEndpoint = environment match {
                case Uat => "https://kafka-uat.ovo-uat.aivencloud.com:13584"
                case Prd => "https://kafka-prd.ovo-prd.aivencloud.com:21559"
              }

              val schemaRegistry = SchemaRegistryClientSettings(
                schemaRegistryEndpoint,
                "comms-platform-service-user",
                schemaRegistryPassword.value
              )

              val consumer = ConsumerSettings(
                pollTimeout = 500.milliseconds,
                maxParallelism = Int.MaxValue,
                nativeSettings = kafkaSSL.setProperties(
                  Map(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaBootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG -> "comms-composer",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
                  )) { (acc, k, v) =>
                  acc + (k -> v)
                }
              )

              val producer = ProducerSettings(
                nativeSettings = kafkaSSL.setProperties(
                  Map(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaBootstrapServers
                  )) { (acc, k, v) =>
                  acc + (k -> v)
                }
              )

              KafkaConfig(
                topics,
                consumer,
                producer,
                schemaRegistry
              )

            }

            Config(
              http,
              kafka,
              store,
              templates,
              docRaptor
            )
        }

      case _ =>
        loadConfig(
          awsF[F, Region](AwsRegion),
          envF[F, Option[Uri]]("S3_ENDPOINT"),
          envF[F, Bucket]("TEMPLATES_S3_BUCKET"),
          envF[F, Bucket]("RENDERED_S3_BUCKET"),
          envF[F, String]("KAFKA_BOOTSTRAP_SERVERS"),
          envF[F, String]("SCHEMA_REGISTRY_ENDPOINT"),
          envF[F, Option[Uri]]("DOCRAPTOR_ENDPOINT")
            .mapValue(_.getOrElse(Uri.uri("https://docraptor.com"))),
          envF[F, Secret[String]]("DOCRAPTOR_API_KEY"),
          envF[F, Option[Boolean]]("DOCRAPTOR_IS_TEST").mapValue(_.getOrElse(true))
        ) {
          (
              awsRegion,
              s3Endpoint,
              templatesBucket,
              storeBucket,
              kafkaBootstrapServers,
              schemaRegistryEndpoint,
              docraptorEndpoint,
              docraptorApiKey,
              docraptorIsTest,
          ) =>
            val docraptor = DocRaptorConfig(
              url = docraptorEndpoint,
              apiKey = docraptorApiKey.value,
              isTest = docraptorIsTest
            )

            val store = Store.Config(storeBucket, awsRegion, s3Endpoint)

            val templates = TemplatesConfig(templatesBucket)

            val kafka = {

              val schemaRegistry = SchemaRegistryClientSettings(
                schemaRegistryEndpoint
              )

              val consumer = ConsumerSettings(
                pollTimeout = 500.milliseconds,
                maxParallelism = Int.MaxValue,
                nativeSettings = Map(
                  ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaBootstrapServers,
                  ConsumerConfig.GROUP_ID_CONFIG -> "comms-composer",
                  ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
                  ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
                )
              )

              val producer = ProducerSettings(
                nativeSettings = Map(
                  ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaBootstrapServers
                )
              )

              KafkaConfig(
                topics,
                consumer,
                producer,
                schemaRegistry
              )

            }

            Config(
              http,
              kafka,
              store,
              templates,
              docraptor
            )
        }

    }.orRaiseThrowable
  }

}
