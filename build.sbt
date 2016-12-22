name := "composer"
organization := "com.ovoenergy"

scalaVersion := "2.11.8"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// Make ScalaTest write test reports that CircleCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF", "-u", testReportsDir, "-l", "DockerComposeTag")

resolvers += Resolver.bintrayRepo("ovotech", "maven")
resolvers += Resolver.bintrayRepo("cakesolutions", "maven")

val kafkaMessagesVersion = "0.0.14"
libraryDependencies ++= Seq(
  "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion,
  "com.ovoenergy" %% "comms-kafka-serialisation" % kafkaMessagesVersion,
  "com.github.jknack" % "handlebars" % "4.0.6",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.57",
  "net.cakesolutions" %% "scala-kafka-client" % "0.10.0.0",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.13",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.14",
  "org.typelevel" %% "cats-free" % "0.8.1",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "io.logz.logback" % "logzio-logback-appender" % "1.0.11",
  "me.moocar" % "logback-gelf" % "0.2",
  "org.scalatest" %% "scalatest" % "3.0.1" % Test,
  "org.apache.kafka" %% "kafka" % "0.10.0.1" % Test
)

enablePlugins(JavaServerAppPackaging, DockerPlugin)

scalafmtConfig in ThisBuild := Some(file(".scalafmt.conf"))
reformatOnCompileSettings

// Service tests
enablePlugins(DockerComposePlugin)
testTagsToExecute := "DockerComposeTag"
dockerImageCreationTask := (publishLocal in Docker).value

lazy val ipAddress: String = {
  val addr = "./get_ip_address.sh".!!.trim
  println(s"My IP address appears to be $addr")
  addr
}
variablesForSubstitution := Map("IP_ADDRESS" -> ipAddress)
