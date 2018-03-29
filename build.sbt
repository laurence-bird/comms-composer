import Dependencies._

name := "composer"
organization := "com.ovoenergy"

scalaVersion := "2.12.4"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-Ypartial-unification")

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


val Http4sVersion = "0.18.4"

libraryDependencies ++= Seq(
//    "com.typesafe.akka" %% "akka-stream-kafka" % "0.17",
//  fs2.core,
//  fs2.io,
  fs2.kafkaClient,
  circe.core,
  circe.generic,
  circe.parser,
  circe.literal,
  http4s.core,
  http4s.circe,
  http4s.dsl,
  http4s.server,
  http4s.blazeServer,

  kafkaSerialization.cats,

  ovoEnergy.commsMessages,
  ovoEnergy.commsHelpers,
  ovoEnergy.commsSerialisation,
  ovoEnergy.commsTemplates,


  "com.github.jknack"     %   "handlebars"              % "4.0.6",
  "com.amazonaws"         %   "aws-java-sdk-s3"         % "1.11.57",
  "com.typesafe.akka"     %%  "akka-slf4j"              % "2.4.18",
  "org.typelevel"         %%  "cats-free"               % "0.9.0",
  "com.chuusai"           %%  "shapeless"               % "2.3.2",
  "com.squareup.okhttp3"  %   "okhttp"                  % "3.4.2",
  "ch.qos.logback"        %   "logback-classic"         % "1.1.7",
  "io.logz.logback"       %   "logzio-logback-appender" % "1.0.11",
  "me.moocar"             %   "logback-gelf"            % "0.2",


  http4s.blazeClient % Test,
  "com.github.alexarchambault"  %% "scalacheck-shapeless_1.13"    % "1.1.4"         % Test exclude("org.slf4j", "log4j-over-slf4j"),
  "com.fortysevendeg"           %% "scalacheck-toolbox-datetime"  % "0.2.1"         % Test,
  "org.scalacheck"              %% "scalacheck"                   % "1.13.4"        % Test,
  "org.scalatest"               %% "scalatest"                    % "3.0.1"         % Test,
  "org.apache.kafka"            %% "kafka"                        % "0.10.2.1"      % Test exclude ("org.scalatest", "scalatest"),
  "org.mock-server"             %  "mockserver-client-java"       % "3.11"          % Test,


  whisk.scalaTest   % ServiceTest,
  whisk.dockerJava  % ServiceTest,
  ovoEnergy.commsTestHelpers % ServiceTest
)

enablePlugins(JavaServerAppPackaging, DockerPlugin)
commsPackagingMaxMetaspaceSize := 128
dockerExposedPorts += 8080

val scalafmtAll = taskKey[Unit]("Run scalafmt in non-interactive mode with no arguments")
scalafmtAll := {
  import org.scalafmt.bootstrap.ScalafmtBootstrap
  streams.value.log.info("Running scalafmt ...")
  ScalafmtBootstrap.main(Seq("--non-interactive"))
  streams.value.log.info("Done")
}
(compile in Compile) := (compile in Compile).dependsOn(scalafmtAll).value

