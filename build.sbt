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
  "com.ovoenergy" %% "comms-kafka-messages" % "1.18",
  "com.ovoenergy" %% "comms-kafka-serialisation" % "2.2",
  "io.circe" %% "circe-generic" % "0.7.0",
  "com.github.jknack" % "handlebars" % "4.0.6",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.57",
  "net.cakesolutions" %% "scala-kafka-client" % "0.10.0.0",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.13",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.14",
  "org.typelevel" %% "cats-free" % "0.9.0",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "io.logz.logback" % "logzio-logback-appender" % "1.0.11",
  "me.moocar" % "logback-gelf" % "0.2",
  "com.ovoenergy" %% "comms-templates" % "0.4",
  "org.scalatest" %% "scalatest" % "3.0.1" % Test,
  "org.apache.kafka" %% "kafka" % "0.10.0.1" % Test
)

enablePlugins(JavaServerAppPackaging, DockerPlugin)

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

val scalafmtAll = taskKey[Unit]("Run scalafmt in non-interactive mode with no arguments")
scalafmtAll := {
  import org.scalafmt.bootstrap.ScalafmtBootstrap
  streams.value.log.info("Running scalafmt ...")
  ScalafmtBootstrap.main(Seq("--non-interactive"))
  streams.value.log.info("Done")
}
(compile in Compile) := (compile in Compile).dependsOn(scalafmtAll).value
