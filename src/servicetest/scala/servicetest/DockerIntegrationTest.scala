package servicetest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDateTime
import java.util.concurrent.Executors

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.jaxrs.JerseyDockerCmdExecFactory
import com.whisk.docker.impl.dockerjava.{Docker, DockerJavaExecutorFactory, DockerKitDockerJava}
import com.whisk.docker.{
  ContainerLink,
  DockerCommandExecutor,
  DockerContainer,
  DockerContainerState,
  DockerFactory,
  DockerReadyChecker,
  LogLineReceiver,
  VolumeMapping
}
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.apache.commons.io.input.{Tailer, TailerListenerAdapter}
import org.apache.kafka.common.protocol.Errors
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

trait DockerIntegrationTest
    extends DockerKitDockerJava
    with KafkaTopics
    with ScalaFutures
    with TestSuite
    with BeforeAndAfterAll
    with Eventually { self =>

  implicit class RichDockerContainer(val dockerContainer: DockerContainer) {

    /**
      * Adds a log line receiver that writes the container output to a file
      * and a ready checker that tails said file and waits for a line containing a given string
      *
      * @param stringToMatch The container is considered ready when a line containing this string is send to stderr or stdout
      * @param containerName An arbitrary name for the container, used for generating the log file name
      * @return
      */
    def withLogWritingAndReadyChecker(stringToMatch: String, containerName: String): DockerContainer = {
      val outputDir = Paths.get("target", "integration-test-logs")
      val outputFile =
        outputDir.resolve(s"${self.getClass.getSimpleName}-$containerName-${LocalDateTime.now().toString}.log")

      val handleLine: String => Unit = (line: String) => {
        val lineWithLineEnding = if (line.endsWith("\n")) line else line + "\n"
        Files.write(outputFile,
                    lineWithLineEnding.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)
      }

      val logLineReceiver = LogLineReceiver(withErr = true, f = handleLine)

      val readyChecker = new DockerReadyChecker {
        override def apply(container: DockerContainerState)(implicit docker: DockerCommandExecutor,
                                                            ec: ExecutionContext): Future[Boolean] = {
          println(s"Waiting for container [$containerName] to become ready. Logs are being streamed to $outputFile.")

          val readyPromise = Promise[Boolean]

          val readyCheckingTailListener = new TailerListenerAdapter {
            var _tailer: Tailer = _

            override def init(tailer: Tailer) = {
              _tailer = tailer
            }

            override def handle(line: String) = {
              if (line.contains(stringToMatch)) {
                println(s"Container [$containerName] is ready")
                readyPromise.trySuccess(true)
                _tailer.stop()
              }
            }
          }

          val tailer = new Tailer(outputFile.toFile, readyCheckingTailListener)
          val thread = new Thread(tailer, s"log tailer for container $containerName")
          thread.start()

          readyPromise.future
        }
      }

      dockerContainer.withLogLineReceiver(logLineReceiver).withReadyChecker(readyChecker)
    }

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

  override val StartContainersTimeout = 5.minutes

  override implicit lazy val dockerExecutionContext: ExecutionContext = {
    // using Math.max to prevent unexpected zero length of docker containers
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(Math.max(1, dockerContainers.length * 4)))
  }

  val hostIpAddress = {
    import sys.process._
    "./get_ip_address.sh".!!.trim
  }

  // TODO currently no way to set the memory limit on docker containers. Need to make a PR to add support to docker-it-scala. I've checked that the spotify client supports it.

  lazy val legacyZookeeper = DockerContainer("confluentinc/cp-zookeeper:3.1.1", name = Some("legacyZookeeper"))
    .withPorts(32181 -> Some(32181))
    .withEnv(
      "ZOOKEEPER_CLIENT_PORT=32181",
      "ZOOKEEPER_TICK_TIME=2000",
      "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M"
    )
    .withLogWritingAndReadyChecker("binding to port", "legacyZookeeper")

  lazy val legacyKafka = {
    // create each topic with 1 partition and replication factor 1
    val createTopicsString = legacyTopics.map(t => s"$t:1:1").mkString(",")

    val lastTopicName = legacyTopics.last

    DockerContainer("wurstmeister/kafka:0.10.1.0", name = Some("legacyKafka"))
      .withPorts(29092 -> Some(29092))
      .withLinks(ContainerLink(legacyZookeeper, "legacyZookeeper"))
      .withEnv(
        "KAFKA_BROKER_ID=1",
        "KAFKA_ZOOKEEPER_CONNECT=legacyZookeeper:32181",
        "KAFKA_PORT=29092",
        "KAFKA_ADVERTISED_PORT=29092",
        s"KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$hostIpAddress:29092",
        "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M",
        s"KAFKA_CREATE_TOPICS=$createTopicsString"
      )
      .withLogWritingAndReadyChecker(s"""Created topic "$lastTopicName"""", "legacyKafka")
  }

  lazy val aivenZookeeper = DockerContainer("confluentinc/cp-zookeeper:3.1.1", name = Some("aivenZookeeper"))
    .withPorts(32182 -> Some(32182))
    .withEnv(
      "ZOOKEEPER_CLIENT_PORT=32182",
      "ZOOKEEPER_TICK_TIME=2000",
      "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M"
    )
    .withLogWritingAndReadyChecker("binding to port", "aivenZookeeper")

  lazy val aivenKafka = {
    // create each topic with 1 partition and replication factor 1
    val createTopicsString = aivenTopics.map(t => s"$t:1:1").mkString(",")

    val lastTopicName = aivenTopics.last

    DockerContainer("wurstmeister/kafka:0.10.2.1", name = Some("aivenKafka"))
      .withPorts(29093 -> Some(29093))
      .withLinks(ContainerLink(aivenZookeeper, "aivenZookeeper"))
      .withEnv(
        "KAFKA_BROKER_ID=2",
        "KAFKA_ZOOKEEPER_CONNECT=aivenZookeeper:32182",
        "KAFKA_PORT=29093",
        "KAFKA_ADVERTISED_PORT=29093",
        s"KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$hostIpAddress:29093",
        "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M",
        s"KAFKA_CREATE_TOPICS=$createTopicsString"
      )
      .withLogWritingAndReadyChecker(s"""Created topic "$lastTopicName"""", "aivenKafka")
  }

  lazy val schemaRegistry = DockerContainer("confluentinc/cp-schema-registry:3.2.2", name = Some("schema-registry"))
    .withPorts(8081 -> Some(8081))
    .withLinks(
      ContainerLink(aivenZookeeper, "aivenZookeeper"),
      ContainerLink(aivenKafka, "aivenKafka")
    )
    .withEnv(
      "SCHEMA_REGISTRY_HOST_NAME=schema-registry",
      "SCHEMA_REGISTRY_KAFKASTORE_CONNECTION_URL=aivenZookeeper:32182",
      s"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=PLAINTEXT://$hostIpAddress:29093"
    )
    .withLogWritingAndReadyChecker("Server started, listening for requests", "schema-registry")

  lazy val fakes3 = DockerContainer("lphoward/fake-s3:latest", name = Some("fakes3"))
    .withPorts(4569 -> Some(4569))
    .withLogWritingAndReadyChecker("WEBrick::HTTPServer#start", "fakes3")

  lazy val fakes3ssl = DockerContainer("cbachich/ssl-proxy:latest", name = Some("fakes3ssl"))
    .withPorts(443 -> Some(443))
    .withLinks(ContainerLink(fakes3, "proxyapp"))
    .withEnv(
      "PORT=443",
      "TARGET_PORT=4569"
    )
    .withLogWritingAndReadyChecker("Starting Proxy: 443", "fakes3ssl")

  lazy val composer = {
    val envVars = List(
      sys.env.get("AWS_ACCESS_KEY_ID").map(envVar => s"AWS_ACCESS_KEY_ID=$envVar"),
      sys.env.get("AWS_ACCOUNT_ID").map(envVar => s"AWS_ACCOUNT_ID=$envVar"),
      sys.env.get("AWS_SECRET_ACCESS_KEY").map(envVar => s"AWS_SECRET_ACCESS_KEY=$envVar"),
      Some("ENV=LOCAL"),
      Some("KAFKA_HOSTS_LEGACY=legacyKafka:29092"),
      Some("KAFKA_HOSTS_AIVEN=aivenKafka:29093"),
      Some("MAILGUN_API_KEY=my_super_secret_api_key"),
      Some("DOCKER_COMPOSE=true"),
      Some("SCHEMA_REGISTRY_URL=http://schema-registry:8081")
    ).flatten

    val awsAccountId = sys.env.getOrElse(
      "AWS_ACCOUNT_ID",
      sys.error("Environment variable AWS_ACCOUNT_ID must be set in order to run the integration tests"))
    DockerContainer(s"$awsAccountId.dkr.ecr.eu-west-1.amazonaws.com/composer:0.1-SNAPSHOT", name = Some("composer"))
      .withPorts(8080 -> Some(8080))
      .withLinks(
        ContainerLink(legacyZookeeper, "legacyZookeeper"),
        ContainerLink(legacyKafka, "legacyKafka"),
        ContainerLink(aivenKafka, "aivenKafka"),
        ContainerLink(aivenZookeeper, "aivenZookeeper"),
        ContainerLink(schemaRegistry, "schema-registry"),
        ContainerLink(fakes3ssl, "ovo-comms-templates.s3-eu-west-1.amazonaws.com")
      )
      .withEnv(envVars: _*)
      .withVolumes(List(VolumeMapping(host = s"${sys.env("HOME")}/.aws", container = "/sbin/.aws"))) // share AWS creds so that credstash works
      .withLogWritingAndReadyChecker("Composer now running", "composer") // TODO check topics/consumers in the app and output a log when properly ready
  }

  override def dockerContainers =
    List(legacyZookeeper, legacyKafka, aivenZookeeper, aivenKafka, schemaRegistry, fakes3, fakes3ssl, composer)

  lazy val legacyZkUtils = ZkUtils("localhost:32181", 30000, 5000, isZkSecurityEnabled = false)
  lazy val aivenZkUtils = ZkUtils("localhost:32182", 30000, 5000, isZkSecurityEnabled = false)

  def checkKafkaTopic(topic: String, zkUtils: ZkUtils, description: String) = {
    println(s"Checking we can retrieve metadata about topic $topic on $description ZooKeeper")
    eventually {
      val topicInfo = AdminUtils.fetchTopicMetadataFromZk(topic, zkUtils)
      val error = topicInfo.error()
      if (Errors.NONE != topicInfo.error()) {
        fail(s"${topicInfo.topic()} encountered an error: $error")
      }
    }
  }

  abstract override def beforeAll(): Unit = {
    super.beforeAll()

    import scala.collection.JavaConverters._
    val logDir = Paths.get("target", "integration-test-logs")
    if (Files.exists(logDir))
      Files.list(logDir).iterator().asScala.foreach(Files.delete)
    else
      Files.createDirectories(logDir)

    println(
      "Starting a whole bunch of Docker containers. This could take a few minutes, but I promise it'll be worth the wait!")
    startAllOrFail()

    legacyTopics.foreach(t => checkKafkaTopic(t, legacyZkUtils, "legacy"))
    aivenTopics.foreach(t => checkKafkaTopic(t, aivenZkUtils, "Aiven"))
  }

  abstract override def afterAll(): Unit = {
    Try {
      legacyZkUtils.close()
      aivenZkUtils.close()
    }

    println("Stopping docker containers")
    stopAllQuietly()

    val dockerClient = new Docker(DefaultDockerClientConfig.createDefaultConfigBuilder().build()).client

    //List all containers including exited and remove
    dockerClient.listContainersCmd().withShowAll(true).exec().asScala.foreach { container =>
      println(s"Trying to remove container ${container.toString} manually, as left hanging around")
      try {
        if (dockerClient.listContainersCmd().withShowAll(true).exec().asScala.contains(container)) {
          dockerClient.removeContainerCmd(container.getId).withForce(true).withRemoveVolumes(true).exec()
          println(s"Removed container ${container.toString}")
        } else {
          println(s"Did not remove container ${container.toString}, as was gone when tried to remove it")
        }
      } catch {
        case e: Throwable => fail(s"Unable to remove container - ${container.toString}", e)
      }
    }

    dockerClient.close()

    super.afterAll()
  }

}
