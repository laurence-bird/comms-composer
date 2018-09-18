import Dependencies._

name := "composer"
organization := "com.ovoenergy.comms"

scalaVersion := "2.12.6"
scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-encoding", "utf8",
  "-language:higherKinds",
  "-Ypartial-unification",
)

// Make ScalaTest write test reports that CircleCI understands
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF")
lazy val ServiceTest = config("servicetest") extend(Test)
lazy val It = config("it") extend Test

configs(ServiceTest, It)
inConfig(It)(Defaults.testSettings)
inConfig(ServiceTest)(Defaults.testSettings)

inConfig(ServiceTest)(parallelExecution in test := false)
inConfig(ServiceTest)(parallelExecution in testOnly := false)
(test in ServiceTest) := (test in ServiceTest).dependsOn(publishLocal in Docker).value

resolvers ++= Seq(
  Resolver.bintrayRepo("ovotech", "maven"),
  "confluent-release" at "http://packages.confluent.io/maven/",
  Resolver.bintrayRepo("cakesolutions", "maven")
)

libraryDependencies ++= Seq(

  fs2.core,
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
  ovoEnergy.commsMessagesTests,
  ovoEnergy.commsHelpers,
  ovoEnergy.commsSerialisation,
  ovoEnergy.commsTemplates,

  handlebars,
  s3Sdk,
  shapeless,
  okhttp,

  logging.logbackClassic,
  logging.logzIoLogbackAppender,
  logging.logbackGelf,

  http4s.blazeClient            % Test,
  scalacheck.shapeless          % Test exclude("org.slf4j", "log4j-over-slf4j"),
  scalacheck.toolboxDatetime    % Test,
  scalacheck.scalacheck         % Test,
  scalatest                     % Test,
  mockserver                    % Test,
  ovoEnergy.dockerKit           % Test,

  whisk.scalaTest               % ServiceTest,
  whisk.dockerJava              % ServiceTest,
  ovoEnergy.commsTestHelpers    % ServiceTest,
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7")
enablePlugins(JavaServerAppPackaging, DockerPlugin)
commsPackagingMaxMetaspaceSize := 128
dockerExposedPorts += 8080

scalafmtOnCompile := true

version ~= (_.replace('+', '-'))
dynver ~= (_.replace('+', '-'))

