import sbt._

object Dependencies {

  lazy val handlebars = "com.github.jknack" % "handlebars" % "4.0.6"
  lazy val s3Sdk = "com.amazonaws" % "aws-java-sdk-s3" % "1.11.419"
  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"

  object scalacheck {
    lazy val shapeless = "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % "1.1.4"
    lazy val toolboxDatetime = "com.fortysevendeg" %% "scalacheck-toolbox-datetime" % "0.2.1"
    lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.5"
  }

  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.0.5"

  object logging {
    lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.3"
    lazy val logzIoLogbackAppender = "io.logz.logback" % "logzio-logback-appender" % "1.0.11"
    lazy val logbackGelf = "me.moocar" % "logback-gelf" % "0.2"
  }

  object fs2 {
    private val fs2Version = "0.10.6"
    private val fs2KafkaClientVersion = "0.1.19"

    lazy val core = "co.fs2" %% "fs2-core" % fs2Version
    lazy val io = "co.fs2" %% "fs2-io" % fs2Version

    lazy val kafkaClient = "com.ovoenergy" %% "fs2-kafka-client" % fs2KafkaClientVersion
  }
  

  object ovoEnergy {

    private val kafkaSerializationVersion = "0.3.11"
    private val commsKafkaTestHelperVersion = "3.18"
    private val commsKafkaMessagesVersion = "1.79.2"
    private val commsTemplatesVersion = "0.28"
    private val commsDockerTestkitVersion = "1.8.4"
    private val commsAwsVersion = "0.1.7-20181001-1151"

    lazy val kafkaSerializationCore = "com.ovoenergy" %% "kafka-serialization-core" % kafkaSerializationVersion
    lazy val kafkaSerializationAvro = "com.ovoenergy" %% "kafka-serialization-avro" % kafkaSerializationVersion
    lazy val kafkaSerializationAvro4s = "com.ovoenergy" %% "kafka-serialization-avro4s" % kafkaSerializationVersion
    lazy val kafkaSerializationCats = "com.ovoenergy" %% "kafka-serialization-cats" % kafkaSerializationVersion

    lazy val commsMessagesTests = "com.ovoenergy" %% "comms-kafka-messages" % commsKafkaMessagesVersion classifier "tests"
    lazy val commsMessages = "com.ovoenergy" %% "comms-kafka-messages" % commsKafkaMessagesVersion
    lazy val commsTemplates = "com.ovoenergy" %% "comms-templates" % commsTemplatesVersion
    lazy val commsTestHelpers = "com.ovoenergy" %% "comms-kafka-test-helpers" % commsKafkaTestHelperVersion
    lazy val commsDockerKit = "com.ovoenergy" %% "comms-docker-testkit" % commsDockerTestkitVersion
    lazy val commsAwsS3 = "com.ovoenergy.comms" %% "comms-aws-s3" % commsAwsVersion
  }

  object circe {
    private val version = "0.9.3"

    lazy val core = "io.circe" %% "circe-core" % version
    lazy val generic = "io.circe" %% "circe-generic" % version
    lazy val parser = "io.circe" %% "circe-parser" % version
    lazy val literal = "io.circe" %% "circe-literal" % version
  }


  object http4s {

    private val version = "0.18.19"

    lazy val core = "org.http4s" %% "http4s-core" % version
    lazy val client = "org.http4s" %% "http4s-client" % version
    lazy val blazeClient = "org.http4s" %% "http4s-blaze-client" % version
    lazy val server = "org.http4s" %% "http4s-server" % version
    lazy val blazeServer = "org.http4s" %% "http4s-blaze-server" % version
    lazy val circe = "org.http4s" %% "http4s-circe" % version
    lazy val dsl = "org.http4s" %% "http4s-dsl" % version
  }

  object ciris {

    private val cirisVersion = "0.10.2"
    private val cirisCredstashVersion = "0.6"
    private val cirisKafkaVersion = "0.10"

    lazy val core = "is.cir" %% "ciris-core" % cirisVersion
    lazy val cats = "is.cir" %% "ciris-cats" % cirisVersion
    lazy val catsEffect = "is.cir" %% "ciris-cats-effect" % cirisVersion
    lazy val credstash = "com.ovoenergy" %% "ciris-credstash" % cirisCredstashVersion
    lazy val kafka = "com.ovoenergy" %% "ciris-aiven-kafka" % cirisKafkaVersion
  }
}
