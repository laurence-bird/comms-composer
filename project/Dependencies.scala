import sbt._

object Dependencies {

  lazy val handlebars = "com.github.jknack" % "handlebars" % "4.1.2"
  lazy val s3Sdk = "com.amazonaws" % "aws-java-sdk-s3" % "1.11.531"
  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock-standalone" % "2.19.0"
  lazy val kafkaClients = "org.apache.kafka" % "kafka-clients" % "2.2.0"
  lazy val catsTime = "io.chrisdavenport" %% "cats-time" % "0.3.0-M1"
  lazy val scalaJava8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0"

  object scalacheck {
    lazy val shapeless = "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % "1.2.2"
    lazy val toolboxDatetime = "com.fortysevendeg" %% "scalacheck-toolbox-datetime" % "0.2.1"
    lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.14.0"
  }

  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.0.6"

  object logging {

    private val slf4jVersion = "1.7.26"

    lazy val log4catsSlf4j = "io.chrisdavenport" %% "log4cats-slf4j" % "0.2.0"
    lazy val log4catsNoop = "io.chrisdavenport" %% "log4cats-noop" % "0.2.0"
    lazy val loggingLog4cats = "com.ovoenergy.comms" %% "logging-log4cats" % "0.1.3"
    lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.3"
    lazy val logzIoLogbackAppender = "io.logz.logback" % "logzio-logback-appender" % "1.0.11"
    lazy val logbackGelf = "me.moocar" % "logback-gelf" % "0.2"
    lazy val log4jOverSlf4j = "org.slf4j" % "log4j-over-slf4j" % slf4jVersion
    lazy val jclOverSlf4j = "org.slf4j" % "jcl-over-slf4j" % slf4jVersion
  }

  object micrometer {
    private val micrometerVersion = "1.1.4"
    lazy val core = "io.micrometer" % "micrometer-core" % micrometerVersion
    lazy val registryDatadog = "io.micrometer" % "micrometer-registry-datadog" % micrometerVersion
  }

  object fs2 {
    private val fs2Version = "1.0.4"
    private val fs2KafkaVersion = "0.19.9"

    lazy val core = "co.fs2" %% "fs2-core" % fs2Version
    lazy val io = "co.fs2" %% "fs2-io" % fs2Version

    lazy val kafkaClient = "com.ovoenergy" %% "fs2-kafka" % fs2KafkaVersion
  }
  

  object ovoEnergy {

    private val kafkaSerializationVersion = "0.5.1"
    private val commsKafkaTestHelperVersion = "3.21"
    private val commsKafkaMessagesVersion = "1.79.4"
    private val commsTemplatesVersion = "0.33"
    private val commsDockerTestkitVersion = "1.9.1"
    private val commsAwsVersion = "0.2.15"
    private val commsDeduplicationVersion = "0.1.9"

    lazy val kafkaSerializationCore = "com.ovoenergy" %% "kafka-serialization-core" % kafkaSerializationVersion
    lazy val kafkaSerializationAvro = "com.ovoenergy" %% "kafka-serialization-avro" % kafkaSerializationVersion
    lazy val kafkaSerializationAvro4s = "com.ovoenergy" %% "kafka-serialization-avro4s" % kafkaSerializationVersion
    lazy val kafkaSerializationCats = "com.ovoenergy" %% "kafka-serialization-cats" % kafkaSerializationVersion

    lazy val commsMessagesTests = "com.ovoenergy" %% "comms-kafka-messages" % commsKafkaMessagesVersion classifier "tests"
    lazy val commsMessages = "com.ovoenergy" %% "comms-kafka-messages" % commsKafkaMessagesVersion
    lazy val commsTemplates = "com.ovoenergy" %% "comms-templates" % commsTemplatesVersion
    lazy val commsTestHelpers = "com.ovoenergy" %% "comms-kafka-test-helpers" % commsKafkaTestHelperVersion
    lazy val commsDockerKit = ("com.ovoenergy" %% "comms-docker-testkit" % commsDockerTestkitVersion)
      .exclude("org.glassfish.jersey.core", "jersey-client")
      .exclude("org.glassfish.jersey.connectors", "jersey-apache-connector")
      .exclude("org.glassfish.jersey.media", "jersey-media-json-jackson")
      .exclude("javax.activation", "activation")
      .exclude("com.google.guava", "guava")
      .exclude("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-json-provider")
      .exclude("com.fasterxml.jackson.datatype", "jackson-datatype-guava")
      .exclude("com.fasterxml.jackson.core", "jackson-databind")
      .exclude("org.apache.httpcomponents", "httpclient")
      .exclude("org.apache.httpcomponents", "httpcore")


    lazy val commsAwsS3 = "com.ovoenergy.comms" %% "comms-aws-s3" % commsAwsVersion
    lazy val commsDeduplication = "com.ovoenergy.comms" %% "deduplication" % commsDeduplicationVersion
  }

  object circe {
    private val version = "0.11.1"

    lazy val core = "io.circe" %% "circe-core" % version
    lazy val generic = "io.circe" %% "circe-generic" % version
    lazy val parser = "io.circe" %% "circe-parser" % version
    lazy val literal = "io.circe" %% "circe-literal" % version
  }


  object http4s {

    private val version = "0.20.0"

    lazy val core = "org.http4s" %% "http4s-core" % version
    lazy val client = "org.http4s" %% "http4s-client" % version
    lazy val blazeClient = "org.http4s" %% "http4s-blaze-client" % version
    lazy val server = "org.http4s" %% "http4s-server" % version
    lazy val blazeServer = "org.http4s" %% "http4s-blaze-server" % version
    lazy val circe = "org.http4s" %% "http4s-circe" % version
    lazy val dsl = "org.http4s" %% "http4s-dsl" % version

    lazy val micrometerMetrics = "com.ovoenergy" %% "http4s-micrometer-metrics" % "0.0.5"
  }

  object ciris {

    private val cirisVersion = "0.12.1"
    private val cirisCredstashVersion = "0.6"
    private val cirisKafkaVersion = "0.13"
    private val cirisAwsSsmVersion = "0.12.1"

    lazy val core = "is.cir" %% "ciris-core" % cirisVersion
    lazy val cats = "is.cir" %% "ciris-cats" % cirisVersion
    lazy val catsEffect = "is.cir" %% "ciris-cats-effect" % cirisVersion
    lazy val credstash = "com.ovoenergy" %% "ciris-credstash" % cirisCredstashVersion
    lazy val kafka = "com.ovoenergy" %% "ciris-aiven-kafka" % cirisKafkaVersion
    lazy val awsSsm = "com.ovoenergy" %% "ciris-aws-ssm" % cirisAwsSsmVersion,
  }
}
