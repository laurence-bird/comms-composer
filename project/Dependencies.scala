import sbt._

object Dependencies {

  object fs2 {
    private val fs2Version              = "0.10.2"
    private val fs2KafkaClientVersion   = "0.1.8"

    lazy val core        =  "co.fs2"            %% "fs2-core"             % fs2Version
    lazy val io          =  "co.fs2"            %% "fs2-io"               % fs2Version

    lazy val kafkaClient =  "com.ovoenergy"     %% "fs2-kafka-client"     % fs2KafkaClientVersion exclude("co.fs2", "fs2-core")
  }

  object kafkaSerialization {
    private val kafkaSerializationVersion = "0.3.6"

    lazy val core    = "com.ovoenergy" %% "kafka-serialization-core" % kafkaSerializationVersion
    lazy val cats    = "com.ovoenergy" %% "kafka-serialization-cats" % kafkaSerializationVersion
    lazy val avro    = "com.ovoenergy" %% "kafka-serialization-avro" % kafkaSerializationVersion
    lazy val avro4s  = "com.ovoenergy" %% "kafka-serialization-avro4s" % kafkaSerializationVersion
  }

  object circe {
    private val version = "0.9.1"

    val core = "io.circe" %% "circe-core" % version
    val generic = "io.circe" %% "circe-generic" % version
    val parser = "io.circe" %% "circe-parser" % version
    val literal = "io.circe" %% "circe-literal" % version
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