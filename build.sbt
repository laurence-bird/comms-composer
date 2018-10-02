import Dependencies._

lazy val ServiceTest = config("servicetest") extend Test
lazy val It = config("it") extend Test

name := "composer"
organization := "com.ovoenergy.comms"

scalaVersion := "2.12.7"
scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-encoding",
  "utf8",
  "-language:higherKinds",
  "-Ypartial-unification",
)

testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF")

configs(ServiceTest, It)
inConfig(It)(Defaults.testSettings)
inConfig(ServiceTest)(Defaults.testSettings)

ServiceTest / test := (ServiceTest / test).dependsOn(Docker / publishLocal).value
ServiceTest / test / parallelExecution := false

resolvers ++= Seq(
  Resolver.bintrayRepo("ovotech", "maven"),
  "confluent-release" at "http://packages.confluent.io/maven/"
)

lazy val awsJavaSdkVersion = "1.11.419"

dependencyOverrides ++= Seq(
  "com.amazonaws" % "aws-java-sdk-core" % awsJavaSdkVersion,
  "com.amazonaws" % "aws-java-sdk-s3" % awsJavaSdkVersion,
  "com.amazonaws" % "aws-java-sdk-dynamodb" % awsJavaSdkVersion,
  "com.amazonaws" % "aws-java-sdk-kms" % awsJavaSdkVersion,
)


libraryDependencies ++= Seq(
  fs2.core,
  fs2.kafkaClient,
  ciris.core,
  ciris.cats,
  ciris.catsEffect,
  ciris.credstash,
  ciris.kafka,
  ciris.kafka,
  circe.core,
  circe.generic,
  circe.parser,
  circe.literal,
  http4s.core,
  http4s.circe,
  http4s.dsl,
  http4s.server,
  http4s.blazeServer,
  http4s.client,
  http4s.blazeClient,
  ovoEnergy.kafkaSerializationCore,
  ovoEnergy.kafkaSerializationCats,
  ovoEnergy.kafkaSerializationAvro,
  ovoEnergy.kafkaSerializationAvro4s,
  ovoEnergy.commsMessages,
  ovoEnergy.commsTemplates,
  ovoEnergy.commsAwsS3,
  handlebars,
  s3Sdk,
  shapeless,
  logging.logbackClassic,
  logging.logzIoLogbackAppender,
  logging.logbackGelf,
  http4s.blazeClient % Test,
  scalacheck.shapeless % Test,
  scalacheck.toolboxDatetime % Test,
  scalacheck.scalacheck % Test,
  scalatest % Test,
  ovoEnergy.commsDockerKit % Test,
  ovoEnergy.commsMessagesTests % Test,
  wiremock % ServiceTest,
  ovoEnergy.commsTestHelpers % ServiceTest,
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7")
enablePlugins(BuildInfoPlugin, JavaServerAppPackaging, DockerPlugin)

commsPackagingDownloadAivenStuffAtStartup := false
commsPackagingMaxMetaspaceSize := 128
dockerExposedPorts += 8080

scalafmtOnCompile := true

version ~= (_.replace('+', '-'))
dynver ~= (_.replace('+', '-'))

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "com.ovoenergy.comms.composer"
