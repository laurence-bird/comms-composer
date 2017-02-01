package com.ovoenergy.comms

import akka.actor.{Actor, ActorSystem, Props, Terminated}
import akka.kafka.ConsumerSettings
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import cats.instances.either._
import com.ovoenergy.comms.aws.TemplateContextFactory
import com.ovoenergy.comms.email.{Composer, Interpreter}
import com.ovoenergy.comms.kafka.ComposerStream
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.comms.serialisation.Decoders._
import io.circe.generic.auto._
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.slf4j.LoggerFactory

object Main extends App {

  val runningInDockerCompose = sys.env.get("DOCKER_COMPOSE").contains("true")

  if (runningInDockerCompose) {
    // accept the self-signed certs from the SSL proxy sitting in front of the fake S3 container
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
  }

  val log = LoggerFactory.getLogger(getClass)

  val config = ConfigFactory.load()

  val templateContext = TemplateContextFactory(runningInDockerCompose, config.getString("aws.region"))

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val kafkaBootstrapServers = config.getString("kafka.bootstrap.servers")

  val input = {
    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, avroDeserializer[OrchestratedEmailV2])
        .withBootstrapServers(kafkaBootstrapServers)
        .withGroupId(config.getString("kafka.group.id"))
    val topic = config.getString("kafka.topics.orchestrated.email.v2")
    ComposerStream.Input(topic, consumerSettings)
  }

  // These outputs are only lazy for the sake of the service tests.
  // We need to construct the producer after the topic has been created,
  // otherwise the tests randomly fail.
  lazy val composedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[ComposedEmail], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.composed.email")
    ComposerStream.Output(topic, producer)
  }

  lazy val failedEmailEventOutput = {
    val producer = KafkaProducer(
      Conf(new StringSerializer, avroSerializer[Failed], bootstrapServers = kafkaBootstrapServers)
    )
    val topic = config.getString("kafka.topics.failed")
    ComposerStream.Output(topic, producer)
  }

  val interpreterFactory = Interpreter.build(templateContext) _
  val emailStream = ComposerStream.build(input, composedEmailEventOutput, failedEmailEventOutput) {
    (orchestratedEmail: OrchestratedEmailV2) =>
      val interpreter = interpreterFactory(orchestratedEmail)
      Composer.program(orchestratedEmail).foldMap(interpreter)
  }

  val decider: Supervision.Decider = { e =>
    log.error("Stopping due to error", e)
    Supervision.Stop
  }

  log.info("Creating email streams")

  val control = emailStream
    .withAttributes(ActorAttributes.supervisionStrategy(decider))
    .to(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
    .run()

  log.info("Started email stream")

  import scala.concurrent.duration._
  val kafkaActorResolve = actorSystem.actorSelection("system/kafka-consumer-1").resolveOne(1.second)
  kafkaActorResolve.foreach { actorRef =>
    log.info(s"Creating an actor to watch $actorRef")
    actorSystem.actorOf(Props(new Actor {
      context.watch(actorRef)
      def receive: Receive = {
        case Terminated(actor) => log.error(s"Uh oh! $actor just died!")
      }
    }), "kafka-watcher")
  }
  kafkaActorResolve.failed.foreach(e => log.warn("Failed to resolve Kafka consumer actor", e))

  control.isShutdown.foreach { _ =>
    log.error("ARGH! The Kafka source has shut down. Killing the JVM and nuking from orbit.")
    System.exit(1)
  }
}
