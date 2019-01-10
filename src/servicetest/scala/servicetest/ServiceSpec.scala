package com.ovoenergy.comms.composer
package servicetest

import cats.Id
import kafka.Kafka._
import com.ovoenergy.comms.model.{Feedback, _}
import email._
import sms._
import print._
import com.ovoenergy.comms.aws.common.model._
import com.ovoenergy.comms.aws.s3.S3
import com.ovoenergy.comms.aws.s3.model._
import com.ovoenergy.comms.dockertestkit._
import com.ovoenergy.comms.aws.common.CredentialsProvider
import com.ovoenergy.kafka.serialization.avro4s._
import com.sksamuel.avro4s._
import cats.implicits._
import cats.effect.{IO, Resource, Timer}
import org.http4s._
import client.Client
import client.blaze.Http1Client
import org.apache.kafka.common.serialization._
import org.apache.kafka.clients.admin._
import org.apache.kafka.clients.consumer._
import org.apache.kafka.clients.producer._
import com.github.tomakehurst.wiremock.client._
import com.ovoenergy.comms.model.types.{ComposedEventV3, OrchestratedEventV3}
import fs2.Stream
import fs2.kafka.{CommittableMessage, KafkaConsumer, KafkaProducer, _}
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
    with Arbitraries
    with BeforeAndAfterEach {

  sys.props.put("logback.configurationFile", "logback-servicetest.xml")

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val contextShift = cats.effect.IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)
  implicit val patience: PatienceConfig = PatienceConfig(scaled(10.seconds), 500.millis)

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

  private lazy val wm: WireMock = WireMock
    .create()
    .host(dockerHostIp)
    .port(wiremockPublicHttpPort)
    .build()

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

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    wm.resetMappings()
  }

  def withHttpClient[A](f: Client[IO] => IO[A]): IO[A] = {
    Http1Client.stream[IO]().evalMap(f).compile.lastOrError
  }

  def withS3[A](f: S3[IO] => IO[A]): IO[A] = withHttpClient { client =>
    val s3 = S3[IO](
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
      .bracket(createClient)(c => IO(c.close()))
      .evalMap(f)
      .compile
      .lastOrError
  }

  def producerS[A: SchemaFor: ToRecord]: fs2.Stream[IO, KafkaProducer[IO, String, A]] = {

    val producerSettings = ProducerSettings(
      new StringSerializer,
      avroBinarySchemaIdSerializer[A](
        schemaRegistryPublicEndpoint,
        isKey = false,
        includesFormatByte = true)
    ).withBootstrapServers(kafkaPublicEndpoint)

    producerStream[IO].using(producerSettings)
  }

  def consumerS[A: SchemaFor: FromRecord]: fs2.Stream[IO, KafkaConsumer[IO, String, A]] = {

    val consumerSettings = ConsumerSettings(
      new StringDeserializer,
      avroBinarySchemaIdWithReaderSchemaDeserializer[A](
        schemaRegistryPublicEndpoint,
        isKey = false,
        includesFormatByte = true))
      .withBootstrapServers(kafkaPublicEndpoint)
      .withEnableAutoCommit(false)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withGroupId(getClass.getName)
      .withPollTimeout(500.millis)

    consumerStream[IO]
      .using(consumerSettings)
  }

  def positiveTest[A <: OrchestratedEventV3: SchemaFor: ToRecord, B <: ComposedEventV3: SchemaFor: FromRecord](sourceMessage: A, topicA: Topic[A], topicB: Topic[B])(assertions: CommittableMessage[IO, String, B] => Assertion) = {

    val record = new ProducerRecord(topicA.name, sourceMessage.metadata.commId, sourceMessage)
    val pm = ProducerMessage.single[Id].of(record)

    val message: CommittableMessage[IO, String, B] = (for {
      _        <- Stream.eval(uploadTemplateToS3(sourceMessage.metadata.templateManifest))
      producer <- producerS[A]
      consumer <- consumerS[B].evalTap(_.subscribeTo(topicB.name))
      _        <- Stream.eval(producer.produce(pm))
      consumed <- consumer.stream.head
    } yield consumed).compile.lastOrError.futureValue

    assertions(message)
  }

  def negativeTest[A <: OrchestratedEventV3: SchemaFor: ToRecord](sourceMessage: A, topicA: Topic[A])(assertions: (CommittableMessage[IO, String, FailedV3], CommittableMessage[IO, String, Feedback]) => Assertion) = {

    val record = new ProducerRecord(topicA.name, sourceMessage.metadata.commId, sourceMessage)
    val pm = ProducerMessage.single[Id].of(record)

    val (failed, feedback) = (for {
      producer <- producerS[A]
      failedConsumer <- consumerS[FailedV3].evalTap(_.subscribeTo(topics.failed.name))
      feedbackConsumer <- consumerS[Feedback].evalTap(_.subscribeTo(topics.feedback.name))
      _        <- Stream.eval(producer.produce(pm))
      failed <- failedConsumer.stream.head
      feedback <- feedbackConsumer.stream.head
    } yield (failed, feedback)).compile.lastOrError.futureValue

    assertions(failed, feedback)
  }

  def producerRecord[A](topic: Topic[A])(message: A, key: A => String) =
    new ProducerRecord[String, A](topic.name, key(message), message)

  def givenDocRaptorSucceeds: IO[Array[Byte]] = {
    import WireMock._

    import fs2._
    import fs2.io._

    val body: IO[Array[Byte]] =
      readInputStream(IO(getClass.getResourceAsStream("/test.pdf")), chunkSize = 1024, ec).compile
        .fold(Vector.empty[Byte])(_ :+ _)
        .map(_.toArray)

    body.flatTap { xs =>
      IO(
        wm.register(
          post(urlPathEqualTo("/docraptor/docs"))
            .willReturn(aResponse().withBody(xs))
        )
      )
    }
  }

  def givenDocRaptorFails(statusCode: Int): IO[Unit] = {
    import WireMock._

    IO(
      wm.register(
        post(urlPathEqualTo("/docraptor/docs"))
          .willReturn(status(statusCode))
      )
    )
  }
}
