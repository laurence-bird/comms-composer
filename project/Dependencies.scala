import sbt._

object Dependencies {

  object fs2 {
    private val fs2Version = "0.10.2"
    private val fs2KafkaClientVersion = "0.1.8"

    lazy val core = "co.fs2" %% "fs2-core" % fs2Version
    lazy val io = "co.fs2" %% "fs2-io" % fs2Version

    lazy val kafkaClient = "com.ovoenergy" %% "fs2-kafka-client" % fs2KafkaClientVersion exclude ("co.fs2", "fs2-core")
  }

  object kafkaSerialization {
    private val kafkaSerializationVersion = "0.3.6"

    lazy val core = "com.ovoenergy" %% "kafka-serialization-core" % kafkaSerializationVersion
    lazy val cats = "com.ovoenergy" %% "kafka-serialization-cats" % kafkaSerializationVersion
    lazy val avro = "com.ovoenergy" %% "kafka-serialization-avro" % kafkaSerializationVersion
    lazy val avro4s = "com.ovoenergy" %% "kafka-serialization-avro4s" % kafkaSerializationVersion
  }

  object ovoEnergy {
    private val kafkaMessagesVersion = "1.38"
    private val kafkaSerialisationVersion = "3.12"
    private val templateVersion = "0.17"

    lazy val commsMessages = "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion
    lazy val commsTemplates = "com.ovoenergy" %% "comms-templates" % templateVersion
    lazy val commsSerialisation = "com.ovoenergy" %% "comms-kafka-serialisation" % kafkaSerialisationVersion exclude ("com.typesafe.akka", "akka-stream-kafka_2.12")
    lazy val commsHelpers = "com.ovoenergy" %% "comms-kafka-helpers" % kafkaSerialisationVersion exclude ("com.typesafe.akka", "akka-stream-kafka_2.12")
    lazy val commsTestHelpers = "com.ovoenergy" %% "comms-kafka-test-helpers" % kafkaSerialisationVersion
  }

  object circe {
    private val version = "0.9.1"

    lazy val core = "io.circe" %% "circe-core" % version
    lazy val generic = "io.circe" %% "circe-generic" % version
    lazy val parser = "io.circe" %% "circe-parser" % version
    lazy val literal = "io.circe" %% "circe-literal" % version
  }

  object whisk {
    private val version = "0.9.6"

    lazy val scalaTest = "com.whisk" %% "docker-testkit-scalatest" % version
    lazy val dockerJava = "com.whisk" %% "docker-testkit-impl-docker-java" % version
  }

  object http4s {

    private val version = "0.18.0"

    lazy val core = "org.http4s" %% "http4s-core" % version
    lazy val client = "org.http4s" %% "http4s-client" % version
    lazy val blazeClient = "org.http4s" %% "http4s-blaze-client" % version
    lazy val server = "org.http4s" %% "http4s-server" % version
    lazy val blazeServer = "org.http4s" %% "http4s-blaze-server" % version
    lazy val circe = "org.http4s" %% "http4s-circe" % version
    lazy val dsl = "org.http4s" %% "http4s-dsl" % version
  }
}
