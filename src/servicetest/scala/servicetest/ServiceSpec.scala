package com.ovoenergy.comms.composer
package servicetest

import kafka.KafkaStream._

import com.ovoenergy.comms.model._
import email._
import sms._
import print._

import com.ovoenergy.comms.aws.common.model._
import com.ovoenergy.comms.aws.s3.S3
import com.ovoenergy.comms.aws.s3.model._
import com.ovoenergy.comms.dockertestkit._
import com.ovoenergy.comms.aws.common.CredentialsProvider

import com.ovoenergy.fs2.kafka._
import com.ovoenergy.kafka.serialization.avro4s._

import com.sksamuel.avro4s._

import cats.implicits._
import cats.effect.IO

import org.http4s._
import client.Client
import client.blaze.Http1Client

import org.apache.kafka.common.serialization._
import org.apache.kafka.clients.admin._
import org.apache.kafka.clients.consumer._
import org.apache.kafka.clients.producer._

import org.scalatest._
import org.scalatest.concurrent.Eventually

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class ServiceSpec
    extends WordSpec
    with Matchers
    with IOFutures
    with ZookeeperKit
    with KafkaKit
    with SchemaRegistryKit
    with DynamoDbKit
    with WiremockKit
    with ComposerKit
    with BeforeAndAfterAll
    with Eventually
    with Arbitraries {

  sys.props.put("logback.configurationFile", "logback-servicetest.xml")


  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val patience: PatienceConfig = PatienceConfig(scaled(25.seconds), 500.millis)

  override lazy val managedContainers: ManagedContainers = ManagedContainers(
    zookeeperContainer,
    kafkaContainer,
    schemaRegistryContainer,
    dynamoDbContainer,
    wiremockContainer,
    composerContainer
  )

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

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    withKafkaAdminClient { adminclient =>
      IO(
        adminclient.createTopics(List(
          new NewTopic(topics.orchestratedEmail.name, 1, 1),
          new NewTopic(topics.orchestratedSms.name, 1, 1),
          new NewTopic(topics.orchestratedPrint.name, 1, 1),
          new NewTopic(topics.composedEmail.name, 1, 1),
          new NewTopic(topics.composedSms.name, 1, 1),
          new NewTopic(topics.composedPrint.name, 1, 1),
          new NewTopic(topics.failed.name, 1, 1),
          new NewTopic(topics.feedback.name, 1, 1),
        ).asJava))
    }.futureValue
  }


  def withHttpClient[A](f: Client[IO] => IO[A]): IO[A] = {
    Http1Client.stream[IO]().evalMap(f).compile.lastOrRethrow
  }

  def withS3[A](f: S3[IO] => IO[A]): IO[A] = withHttpClient { client =>
    val s3 = new S3[IO](
      client,
      CredentialsProvider.default[IO],
      Region.`eu-west-1`
    )

    f(s3)
  }

  def uploadTemplateToS3(templateManifest: TemplateManifest): IO[Unit] = withS3 { s3 =>
    val bucketName = Bucket(composerTemplatesS3Bucket)


    List(
      s3.putObject(
          bucketName,
          Key(s"${templateManifest.id}/${templateManifest.version}/email/subject.txt"),
          ObjectContent.fromByteArray[IO]("SUBJECT {{profile.firstName}}".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"${templateManifest.id}/${templateManifest.version}/email/body.html"),
          ObjectContent.fromByteArray[IO]("{{> header}} HTML BODY {{amount}}".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"${templateManifest.id}/${templateManifest.version}/email/body.txt"),
          ObjectContent.fromByteArray[IO]("{{> header}} TEXT BODY {{amount}}".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"${templateManifest.id}/${templateManifest.version}/sms/body.txt"),
          ObjectContent.fromByteArray[IO]("{{> header}} SMS BODY {{amount}}".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"${templateManifest.id}/${templateManifest.version}/print/body.html"),
          ObjectContent.fromByteArray[IO]("Hello {{profile.firstName}}".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"fragments/email/html/header.html"),
          ObjectContent.fromByteArray[IO]("HTML HEADER".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"fragments/email/txt/header.txt"),
          ObjectContent.fromByteArray[IO]("TEXT HEADER".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow,
      s3.putObject(
          bucketName,
          Key(s"fragments/sms/txt/header.txt"),
          ObjectContent.fromByteArray[IO]("SMS HEADER".getBytes)
        )
        .map(_.leftWiden[Throwable])
        .rethrow
    ).sequence.void

  }

  def withKafkaAdminClient[A](f: AdminClient => IO[A]): IO[A] = {
    val createClient = IO(
      AdminClient.create(
        Map[String, AnyRef](
          AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaPublicEndpoint,
        ).asJava))
    fs2.Stream
      .bracket(createClient)(c => fs2.Stream.emit(c), c => IO(c.close()))
      .evalMap(f)
      .compile
      .lastOrRethrow
  }

  def withProducerFor[A: SchemaFor: ToRecord, B](topic: Topic[A])(
    f: Producer[String, A] => IO[B]): IO[B] = {

    val producerSettings = ProducerSettings(
      Map(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaPublicEndpoint,
      )
    )

    producerStream[IO]
      .apply(
        producerSettings,
        new StringSerializer,
        avroBinarySchemaIdSerializer[A](
          schemaRegistryPublicEndpoint,
          isKey = false,
          includesFormatByte = true),
      )
      .evalMap(f)
      .compile
      .lastOrRethrow
  }

  def withConsumerFor[A: SchemaFor: FromRecord, B](topic: Topic[A])(
    f: Consumer[String, A] => IO[B]): IO[B] = {

    val consumerSettings = ConsumerSettings(
      pollTimeout = 500.millis,
      Int.MaxValue,
      Map(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaPublicEndpoint,
        ConsumerConfig.GROUP_ID_CONFIG -> getClass.getName,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
      )
    )

    consumerStream[IO]
      .apply(
        new StringDeserializer,
        avroBinarySchemaIdWithReaderSchemaDeserializer[A](
          schemaRegistryPublicEndpoint,
          isKey = false,
          includesFormatByte = true),
        consumerSettings
      )
      .evalMap { c => IO(c.subscribe(List(topic.name).asJava)).as(c)
      }
      .evalMap(f)
      .compile
      .lastOrRethrow
  }

  def consume[A: SchemaFor: FromRecord, B](topic: Topic[A])(
    f: ConsumerRecord[String, A] => IO[B]): fs2.Stream[IO, B] = {

    val consumerSettings = ConsumerSettings(
      pollTimeout = 500.millis,
      Int.MaxValue,
      Map(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaPublicEndpoint,
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
        ConsumerConfig.GROUP_ID_CONFIG -> getClass.getName,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest"
      )
    )

    consumeProcessAndCommit[IO]
      .apply(
        Subscription.topics(topic.name),
        new StringDeserializer,
        avroBinarySchemaIdWithReaderSchemaDeserializer[A](
          schemaRegistryPublicEndpoint,
          isKey = false,
          includesFormatByte = true),
        consumerSettings
      )(f)
  }

}
