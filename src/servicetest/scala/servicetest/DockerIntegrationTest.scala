package servicetest

import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

import cakesolutions.kafka.KafkaConsumer
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.github.tomakehurst.wiremock.client.WireMock
import com.ovoenergy.comms.dockertestkit._
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.{CommManifest, Customer, CustomerAddress, CustomerProfile, InternalMetadata, MetadataV3, TemplateData, TemplateManifest, Service => ServiceComm}
import com.ovoenergy.comms.templates.util.Hash
import com.typesafe.config.{Config, ConfigFactory}
import com.whisk.docker.testkit.{ContainerGroup, ManagedContainers}
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest._
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import servicetest.util.MockTemplates


trait DockerIntegrationTest
  extends ScalaFutures
    with TestSuite
    with BeforeAndAfterAll
    with SchemaRegistryKit
    with KafkaKit
    with ZookeeperKit
    with WiremockKit
    with FakeS3Kit
    with DynamoDbKit
    with ComposerKit
    with Eventually
    with MockTemplates {
  self =>


  implicit val config: Config = ConfigFactory.load("servicetest.conf")
  val kafkaCluster = Kafka.aiven
  val TopicNames: List[String] = kafkaCluster.kafkaConfig.topics.toList.map(_._2)
  val DynamoTableName = "comms-events"

  val validTemplateCommManifest: TemplateManifest = TemplateManifest(
    Hash("composer-service-test"),
    "0.1"
  )

  val invalidTemplateCommManifest: TemplateManifest = TemplateManifest(
    Hash("no-such-template"),
    "9.9"
  )

  val pdfResponseByteArray: Array[Byte] = Files.readAllBytes(Paths.get("src/servicetest/resources/test.pdf"))

  private final val templatesBucket = "ovo-comms-templates"
  private final val pdfBucket = "dev-ovo-comms-pdfs"

  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  val now: Instant = Instant.now()

  val accountNumber: TemplateData = TemplateData.fromString("11112222")
  val ovoId: TemplateData = TemplateData.fromString("myOvo999")


  val orchestratedPrintEvent = OrchestratedPrintV2(
    MetadataV3(
      createdAt = now,
      eventId = "event1234",
      traceToken = "1234567890",
      templateManifest = TemplateManifest(Hash("composer-service-test"), "0.1"),
      commId = "1234",
      friendlyDescription = "very friendly",
      source = "origin",
      canary = true,
      sourceMetadata = None,
      triggerSource = "marketing",
      deliverTo = Customer("12341425")
    ),
    InternalMetadata("8989898989"),
    Some(
      CustomerProfile(
        "Bob",
        "Marley"
      )
    ),
    CustomerAddress(
      "12 Heaven Street",
      None,
      "Kingstown",
      None,
      "12345",
      Some("Jamaica")
    ),
    Map("accountNumber" -> accountNumber, "myOvoId" -> ovoId),
    Some(now.plusSeconds(100))
  )

  lazy val s3Client: AmazonS3 = AmazonS3Client.builder()
    .withEndpointConfiguration(new EndpointConfiguration(fakeS3PublicEndpoint, "eu-west-1"))
    .withPathStyleAccessEnabled(true)
    .withChunkedEncodingDisabled(true)
    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("service-test", "dummy")))
    .build()

  override val managedContainers: ManagedContainers = ContainerGroup.of(
    zookeeperContainer,
    kafkaContainer,
    schemaRegistryContainer,
    wiremockContainer,
    fakeS3Container,
    dynamoDbContainer,
    composerContainer
  )

  def checkCanConsumeFromKafkaTopic(topic: String, bootstrapServers: String, description: String) {
    println(s"Checking we can consume from topic $topic on $description Kafka")
    import cakesolutions.kafka.KafkaConsumer._

    import scala.collection.JavaConverters._
    val consumer = KafkaConsumer(
      Conf[String, String](Map("bootstrap.servers" -> bootstrapServers, "group.id" -> UUID.randomUUID().toString),
        new StringDeserializer,
        new StringDeserializer))
    consumer.assign(List(new TopicPartition(topic, 0)).asJava)
    eventually(PatienceConfiguration.Timeout(Span(20, Seconds))) {
      consumer.poll(200)
    }
    println("Yes we can!")
  }

  def createTopics(topics: Iterable[String], bootstrapServers: String) {
    println(s"Creating kafka topics")
    import scala.collection.JavaConverters._

    val adminClient =
      AdminClient.create(Map[String, AnyRef](AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers).asJava)
    try {
      val r = adminClient.createTopics(topics.map(t => new NewTopic(t, 1, 1)).asJavaCollection)
      r.all().get()
    } catch {
      case e: java.util.concurrent.ExecutionException => ()
    } finally {
      adminClient.close()
    }
  }


  override def afterStart(): Unit = {
    super.afterStart()

    WireMock.configureFor(dockerHostIp, wiremockPublicHttpPort)

    createTopics(TopicNames, s"localhost:$DefaultKafkaPort")
    TopicNames.foreach(t => checkCanConsumeFromKafkaTopic(t, s"localhost:$DefaultKafkaPort", "Aiven"))

    s3Client.createBucket(templatesBucket)
    s3Client.createBucket(pdfBucket)
    uploadTemplateToS3(validTemplateCommManifest, s3Client, templatesBucket)

    uploadTemplateToS3(orchestratedPrintEvent.metadata.templateManifest, s3Client, templatesBucket)


    createOKDocRaptorResponse()
  }

  import WireMock._

  def createOKDocRaptorResponse() {

    stubFor(
      post(urlPathEqualTo("/docs"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(pdfResponseByteArray)
        )
    )

  }

  def create400DocRaptorResponse() {

    stubFor(
      post(urlPathEqualTo("/docs"))
        .willReturn(aResponse()
          .withStatus(400)
          .withBody("<error>Problemo</error>")
        )
    )

  }

}
