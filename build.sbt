import DockerPackage._

name := "composer"
organization := "com.ovoenergy"

scalaVersion := "2.11.8"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// Make ScalaTest write test reports that CircleCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF", "-u", testReportsDir, "-l", "DockerComposeTag")

resolvers += Resolver.bintrayRepo("ovotech", "maven")
resolvers += Resolver.bintrayRepo("cakesolutions", "maven")

libraryDependencies ++= Seq(
  "com.ovoenergy" %% "comms-kafka-messages" % "0.0.10",
  "com.ovoenergy" %% "comms-kafka-serialisation" % "0.0.10",
  "com.github.jknack" % "handlebars" % "4.0.6",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.57",
  "net.cakesolutions" %% "scala-kafka-client" % "0.10.0.0",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.12",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.14",
  "org.typelevel" %% "cats-free" % "0.8.1",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "io.logz.logback" % "logzio-logback-appender" % "1.0.11",
  "me.moocar" % "logback-gelf" % "0.2",
  "org.scalatest" %% "scalatest" % "3.0.1" % Test,
  "org.apache.kafka" %% "kafka" % "0.10.0.1" % Test
)

scalafmtConfig in ThisBuild := Some(file(".scalafmt.conf"))
reformatOnCompileSettings

// Service tests
enablePlugins(DockerComposePlugin)
testTagsToExecute := "DockerComposeTag"

dockerImageCreationTask := (publishLocal in Docker).value

credstashInputDir := file("conf")

lazy val ipAddress: String = {
  val addr = "./get_ip_address.sh".!!.trim
  println(s"My IP address appears to be $addr")
  addr
}

variablesForSubstitution := Map("IP_ADDRESS" -> ipAddress)

lazy val root = (project in file(".")).withDocker
