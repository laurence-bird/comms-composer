package servicetest

import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors

import cakesolutions.kafka.KafkaConsumer
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.jaxrs.JerseyDockerCmdExecFactory
import com.ovoenergy.comms.dockertestkit.DockerContainerExtensions
import com.ovoenergy.comms.helpers.Kafka
import com.typesafe.config.ConfigFactory
import com.whisk.docker.impl.dockerjava.{Docker, DockerJavaExecutorFactory, DockerKitDockerJava}
import com.whisk.docker.{ContainerLink, DockerContainer, DockerFactory, VolumeMapping}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.mockserver.client.server.MockServerClient
import org.scalatest._
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.time.{Seconds, Span}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

trait DockerIntegrationTest
    extends DockerKitDockerJava
    with ScalaFutures
    with TestSuite
    with BeforeAndAfterAll
    with DockerContainerExtensions
    with Eventually { self =>

  def kafkaEndpoint: String = s"$hostIp:$DefaultKafkaPort"
  def legacyKafkaEndpoint: String = s"$hostIp:$DefaultLegacyKafkaPort"
  def schemaRegistryEndpoint = s"http://$hostIp:$DefaultSchemaRegistryPort"
  def composerHttpEndpoint: String = s"http://localhost:${unsafePort(ComposerHttpPort, composer)}"

  implicit val config = ConfigFactory.load("servicetest.conf")
  val TopicNames = Kafka.aiven.kafkaConfig.topics.toList.map(_._2)
  val DynamoTableName = "comms-events"
  val DefaultDynamoDbPort = 8000
  val DefaultKafkaPort = 29093
  val DefaultLegacyKafkaPort = 29094
  val DefaultSchemaRegistryPort = 8081
  val ComposerHttpPort = 8080
  val mockServerClient = new MockServerClient("localhost", 1080)

  override val StartContainersTimeout = 5.minutes
  override val StopContainersTimeout = 1.minute

  override implicit lazy val dockerExecutionContext: ExecutionContext = {
    // using Math.max to prevent unexpected zero length of docker containers
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(Math.max(1, dockerContainers.length * 4)))
  }

  override implicit val dockerFactory: DockerFactory = new DockerJavaExecutorFactory(
    new Docker(
      config = DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
      factory = new JerseyDockerCmdExecFactory()
      // increase connection pool size so we can tail the logs of all containers
        .withMaxTotalConnections(100)
        .withMaxPerRouteConnections(20)
    )
  )

  lazy val hostIp = NetworkInterface.getNetworkInterfaces.asScala
    .filter(x => x.isUp && !x.isLoopback)
    .flatMap(_.getInterfaceAddresses.asScala)
    .map(_.getAddress)
    .find(_.isSiteLocalAddress)
    .fold(throw new RuntimeException("Local ip address not found"))(_.getHostAddress)

  lazy val mockServers = {
    DockerContainer("jamesdbloom/mockserver:mockserver-3.12", name = Some("mockservers"))
      .withPorts(1080 -> Some(1080))
      .withLogWritingAndReadyChecker("MockServer proxy started", "mockservers")
  }

  lazy val zookeeperContainer = DockerContainer("confluentinc/cp-zookeeper:3.1.1", name = Some("zookeeper"))
    .withPorts(32182 -> Some(32182))
    .withEnv(
      "ZOOKEEPER_CLIENT_PORT=32182",
      "ZOOKEEPER_TICK_TIME=2000",
      "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M"
    )
    .withLogWritingAndReadyChecker("binding to port", "zookeeper")

  lazy val kafkaContainer = {
    // create each topic with 1 partition and replication factor 1
    val createTopicsString = TopicNames.map(t => s"$t:1:1").mkString(",")
    val lastTopicName = TopicNames.last

    DockerContainer("wurstmeister/kafka:0.10.2.1", name = Some("kafka"))
      .withPorts(DefaultKafkaPort -> Some(DefaultKafkaPort))
      .withLinks(ContainerLink(zookeeperContainer, "zookeeper"))
      .withEnv(
        "KAFKA_BROKER_ID=2",
        "KAFKA_ZOOKEEPER_CONNECT=zookeeper:32182",
        s"KAFKA_PORT=${DefaultKafkaPort}",
        s"KAFKA_ADVERTISED_PORT=${DefaultKafkaPort}",
        s"KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$hostIp:$DefaultKafkaPort",
        "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M",
        s"KAFKA_CREATE_TOPICS=$createTopicsString"
      )
      .withLogWritingAndReadyChecker(s"""Created topic "$lastTopicName"""", "kafka") // Note: this needs to be the last topic in the list of topics above
  }

  lazy val schemaRegistryContainer =
    DockerContainer("confluentinc/cp-schema-registry:3.2.2", name = Some("schema-registry"))
      .withPorts(DefaultSchemaRegistryPort -> Some(DefaultSchemaRegistryPort))
      .withLinks(
        ContainerLink(zookeeperContainer, "zookeeper"),
        ContainerLink(kafkaContainer, "kafka")
      )
      .withEnv(
        "SCHEMA_REGISTRY_HOST_NAME=schema-registry",
        "SCHEMA_REGISTRY_KAFKASTORE_CONNECTION_URL=zookeeper:32182",
        s"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=PLAINTEXT://$hostIp:$DefaultKafkaPort"
      )
      .withLogWritingAndReadyChecker("Server started, listening for requests", "schema-registry")

  lazy val composer = {
    val envVars = List(
      sys.env.get("AWS_ACCESS_KEY_ID").map(envVar => s"AWS_ACCESS_KEY_ID=$envVar"),
      sys.env.get("AWS_ACCOUNT_ID").map(envVar => s"AWS_ACCOUNT_ID=$envVar"),
      sys.env.get("AWS_SECRET_ACCESS_KEY").map(envVar => s"AWS_SECRET_ACCESS_KEY=$envVar"),
      Some("ENV=LOCAL"),
      Some("KAFKA_HOSTS_AIVEN=aivenKafka:29093"),
      Some("DOCKER_COMPOSE=true"),
      Some("SCHEMA_REGISTRY_URL=http://schema-registry:8081"),
      Some("DOCRAPTOR_URL=http://docraptor:1080")
    ).flatten

    val awsAccountId = sys.env.getOrElse(
      "AWS_ACCOUNT_ID",
      sys.error("Environment variable AWS_ACCOUNT_ID must be set in order to run the integration tests"))
    DockerContainer(s"$awsAccountId.dkr.ecr.eu-west-1.amazonaws.com/composer:0.1-SNAPSHOT", name = Some("composer"))
      .withPorts(ComposerHttpPort -> None)
      .withLinks(
        ContainerLink(kafkaContainer, "aivenKafka"),
        ContainerLink(zookeeperContainer, "aivenZookeeper"),
        ContainerLink(schemaRegistryContainer, "schema-registry"),
        ContainerLink(mockServers, "docraptor"),
        ContainerLink(fakes3ssl, "ovo-comms-templates.s3-eu-west-1.amazonaws.com"),
        ContainerLink(fakes3ssl, "dev-ovo-comms-pdfs.s3-eu-west-1.amazonaws.com")
      )
      .withEnv(envVars: _*)
      .withVolumes(List(VolumeMapping(host = s"${sys.env("HOME")}/.aws", container = "/sbin/.aws"))) // share AWS creds so that credstash works
      .withLogWritingAndReadyChecker("Composer now running", "composer") // TODO check topics/consumers in the app and output a log when properly ready
  }

  // TODO The fake s3 does not have a specific tag, so we have to go with latest
  lazy val fakes3 = {
    DockerContainer("lphoward/fake-s3:latest", name = Some("fakes3"))
      .withPorts(4569 -> Some(4569))
      .withLogWritingAndReadyChecker("WEBrick::HTTPServer#start", "fakes3")
  }

  lazy val fakes3ssl = {
    DockerContainer("cbachich/ssl-proxy:latest", name = Some("fakes3ssl"))
      .withPorts(443 -> Some(443))
      .withLinks(ContainerLink(fakes3, "proxyapp"))
      .withEnv(
        "PORT=443",
        "TARGET_PORT=4569"
      )
      .withLogWritingAndReadyChecker("Starting Proxy: 443", "fakes3ssl")
  }

  override def dockerContainers =
    List(zookeeperContainer, kafkaContainer, schemaRegistryContainer, fakes3, fakes3ssl, mockServers, composer)

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

  abstract override def beforeAll(): Unit = {
    super.beforeAll()

    println(
      "Starting a whole bunch of Docker containers. This could take a few minutes, but I promise it'll be worth the wait!")
    startAllOrFail()
    TopicNames.foreach(t => checkCanConsumeFromKafkaTopic(t, s"localhost:$DefaultKafkaPort", "Aiven"))
  }

  abstract override def afterAll(): Unit = {
    stopAllQuietly()
    super.afterAll()
  }

  def port(internalPort: Int, dockerContainer: DockerContainer): Option[Int] =
    Await.result(dockerContainer
                   .getPorts()
                   .map(ports => ports.get(internalPort)),
                 30.seconds)

  def unsafePort(internalPort: Int, dockerContainer: DockerContainer): Int =
    port(internalPort, dockerContainer)
      .getOrElse(throw new RuntimeException(s"The port $internalPort is not exposed"))
}
