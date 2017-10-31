name := "composer"
organization := "com.ovoenergy"

scalaVersion := "2.12.4"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// Make ScalaTest write test reports that CircleCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF", "-u", testReportsDir)

lazy val ServiceTest = config("servicetest") extend(Test)
configs(ServiceTest)
inConfig(ServiceTest)(Defaults.testSettings)
inConfig(ServiceTest)(parallelExecution in test := false)
inConfig(ServiceTest)(parallelExecution in testOnly := false)
(test in ServiceTest) := (test in ServiceTest).dependsOn(publishLocal in Docker).value

resolvers := Resolver.withDefaultResolvers(
  Seq(
    Resolver.bintrayRepo("ovotech", "maven"),
    "confluent-release" at "http://packages.confluent.io/maven/",
    Resolver.bintrayRepo("cakesolutions", "maven")
  )
)

val kafkaMessagesVersion = "1.34"
val kafkaSerialisationVersion = "3.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.17",
  "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion,
  "com.ovoenergy" %% "comms-kafka-serialisation" % kafkaSerialisationVersion,
  "com.ovoenergy" %% "comms-kafka-helpers"       % kafkaSerialisationVersion,
  "io.circe" %% "circe-generic" % "0.7.0",
  "com.github.jknack" % "handlebars" % "4.0.6",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.57",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.18",
  "org.typelevel" %% "cats-free" % "0.9.0",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "com.squareup.okhttp3" % "okhttp" % "3.4.2",
  "com.fortysevendeg" %% "scalacheck-toolbox-datetime" % "0.2.1" % Test,
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "io.logz.logback" % "logzio-logback-appender" % "1.0.11",
  "me.moocar" % "logback-gelf" % "0.2",
  "com.ovoenergy" %% "comms-templates" % "0.11",
  "com.github.alexarchambault"  %% "scalacheck-shapeless_1.13" % "1.1.4" % Test exclude("org.slf4j", "log4j-over-slf4j"),
  "org.scalacheck" %% "scalacheck" % "1.13.4" % Test,
  "com.whisk"                  %% "docker-testkit-scalatest" % "0.9.3" % ServiceTest,
  "com.whisk"                  %% "docker-testkit-impl-docker-java" % "0.9.3" % ServiceTest,
  "com.ovoenergy" %% "comms-kafka-test-helpers" % kafkaSerialisationVersion % ServiceTest,
  "org.scalatest" %% "scalatest" % "3.0.1" % Test,
  "org.apache.kafka" %% "kafka" % "0.10.2.1"  % Test exclude ("org.scalatest", "scalatest"),
  "org.mock-server"            % "mockserver-client-java"     % "3.11" % Test

)

enablePlugins(JavaServerAppPackaging, DockerPlugin)
commsPackagingMaxMetaspaceSize := 128

val scalafmtAll = taskKey[Unit]("Run scalafmt in non-interactive mode with no arguments")
scalafmtAll := {
  import org.scalafmt.bootstrap.ScalafmtBootstrap
  streams.value.log.info("Running scalafmt ...")
  ScalafmtBootstrap.main(Seq("--non-interactive"))
  streams.value.log.info("Done")
}
(compile in Compile) := (compile in Compile).dependsOn(scalafmtAll).value
