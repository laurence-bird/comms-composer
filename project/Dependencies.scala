import sbt._

object Dependencies {

  lazy val handlebars = "com.github.jknack" % "handlebars" % "4.0.6"
  lazy val s3Sdk = "com.amazonaws" % "aws-java-sdk-s3" % "1.11.57"
  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.2"
  lazy val okhttp = "com.squareup.okhttp3" % "okhttp" % "3.4.2"

  object scalacheck {
    lazy val shapeless = "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % "1.1.4"
    lazy val toolboxDatetime = "com.fortysevendeg" %% "scalacheck-toolbox-datetime" % "0.2.1"
    lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
  }

  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.0.1"
  lazy val mockserver = "org.mock-server" % "mockserver-client-java" % "3.11"

  object logging {
    lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.1.7"
    lazy val logzIoLogbackAppender = "io.logz.logback" % "logzio-logback-appender" % "1.0.11"
    lazy val logbackGelf = "me.moocar" % "logback-gelf" % "0.2"
  }

  object fs2 {
    private val fs2Version = "0.10.5"
    private val fs2KafkaClientVersion = "0.1.9"

    lazy val core = "co.fs2" %% "fs2-core" % fs2Version
    lazy val io = "co.fs2" %% "fs2-io" % fs2Version

    lazy val kafkaClient = "com.ovoenergy" %% "fs2-kafka-client" % fs2KafkaClientVersion
  }

  object kafkaSerialization {
    private val kafkaSerializationVersion = "0.3.6"

    lazy val core = "com.ovoenergy" %% "kafka-serialization-core" % kafkaSerializationVersion
    lazy val cats = "com.ovoenergy" %% "kafka-serialization-cats" % kafkaSerializationVersion
    lazy val avro = "com.ovoenergy" %% "kafka-serialization-avro" % kafkaSerializationVersion
    lazy val avro4s = "com.ovoenergy" %% "kafka-serialization-avro4s" % kafkaSerializationVersion
  }

  object ovoEnergy {
    private val kafkaMessagesVersion = "1.75"
    private val kafkaSerialisationVersion = "3.18"
    private val templateVersion = "0.25"
    private val commsDockerTestkitVersion = "1.8"
    lazy val commsMessagesTests = "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion classifier "tests"
    lazy val commsMessages = "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion
    lazy val commsTemplates = "com.ovoenergy" %% "comms-templates" % templateVersion
    lazy val commsSerialisation = "com.ovoenergy" %% "comms-kafka-serialisation" % kafkaSerialisationVersion exclude ("com.typesafe.akka", "akka-stream-kafka_2.12")
    lazy val commsHelpers = "com.ovoenergy" %% "comms-kafka-helpers" % kafkaSerialisationVersion exclude ("com.typesafe.akka", "akka-stream-kafka_2.12")
    lazy val commsTestHelpers = "com.ovoenergy" %% "comms-kafka-test-helpers" % kafkaSerialisationVersion
    lazy val dockerKit = "com.ovoenergy" %% "comms-docker-testkit" % commsDockerTestkitVersion
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

    private val version = "0.18.18"

    lazy val core = "org.http4s" %% "http4s-core" % version
    lazy val client = "org.http4s" %% "http4s-client" % version
    lazy val blazeClient = "org.http4s" %% "http4s-blaze-client" % version
    lazy val server = "org.http4s" %% "http4s-server" % version
    lazy val blazeServer = "org.http4s" %% "http4s-blaze-server" % version
    lazy val circe = "org.http4s" %% "http4s-circe" % version
    lazy val dsl = "org.http4s" %% "http4s-dsl" % version
  }
}
