import Dependencies._
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.cloudformation.model._

lazy val ServiceTest = config("servicetest") extend Test
lazy val IT = config("it") extend Test

lazy val awsJavaSdkVersion = "1.11.545"
lazy val jerseyVersion = "2.25.1"

lazy val composer = (project in file("."))
  .enablePlugins(BuildInfoPlugin, JavaServerAppPackaging, AshScriptPlugin, DockerPlugin, EcrPlugin, CloudFormationPlugin)
  .configs(ServiceTest, IT)
  .settings(
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.10"),

    name := "composer",
    organization := "com.ovoenergy.comms",
    version ~= (_.replace('+', '-')),
    dynver ~= (_.replace('+', '-')),

    scalaVersion := "2.12.8",
    scalacOptions := Seq(
      "-unchecked",
      "-deprecation",
      "-feature",
      "-encoding",
      "utf8",
      "-language:higherKinds",
      "-Ypartial-unification",
    ),

    testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF"),
    inConfig(IT)(Defaults.testSettings),
    inConfig(ServiceTest)(Defaults.testSettings),
    ServiceTest / test := (ServiceTest / test).dependsOn(Docker / publishLocal).value,
    ServiceTest / testOnly := (ServiceTest / testOnly).dependsOn(Docker / publishLocal).evaluated,
    ServiceTest / test / parallelExecution := false,
    publish := (Docker / publish).value,
    publishLocal := (Docker / publishLocal).value,
    
    resolvers ++= Seq(
      Resolver.bintrayRepo("ovotech", "maven"),
      Resolver.bintrayRepo("cakesolutions", "maven"),
      "confluent-release" at "http://packages.confluent.io/maven/"
    ),

    dependencyOverrides ++= Seq(
      "com.amazonaws" % "aws-java-sdk-core" % awsJavaSdkVersion,
      "com.amazonaws" % "aws-java-sdk-s3" % awsJavaSdkVersion,
      "com.amazonaws" % "aws-java-sdk-dynamodb" % awsJavaSdkVersion,
      "com.amazonaws" % "aws-java-sdk-kms" % awsJavaSdkVersion,
    ),

    excludeDependencies ++= Seq(
      ExclusionRule("commons-logging", "commons-logging"),
      ExclusionRule("org.slf4j", "slf4j-log4j12"),
      ExclusionRule("log4j", "log4j")
    ),

    libraryDependencies ++= Seq(
      catsTime,
      scalaJava8Compat,
      fs2.core,
      fs2.kafkaClient,
      ciris.core,
      ciris.cats,
      ciris.catsEffect,
      ciris.credstash,
      ciris.kafka,
      ciris.awsSsm,
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
      http4s.micrometerMetrics,
      ovoEnergy.kafkaSerializationCore,
      ovoEnergy.kafkaSerializationCats,
      ovoEnergy.kafkaSerializationAvro,
      ovoEnergy.kafkaSerializationAvro4s,
      kafkaClients,
      ovoEnergy.commsMessages,
      ovoEnergy.commsTemplates,
      ovoEnergy.commsAwsS3,
      ovoEnergy.commsDeduplication,
      handlebars,
      s3Sdk,
      shapeless,
      logging.logbackClassic,
      logging.logzIoLogbackAppender,
      logging.logbackGelf,
      logging.log4catsSlf4j,
      logging.log4catsNoop,
      logging.loggingLog4cats,
      logging.log4jOverSlf4j,
      logging.jclOverSlf4j,
      micrometer.core,
      micrometer.registryDatadog,
      http4s.blazeClient % Test,
      scalacheck.shapeless % Test,
      scalacheck.toolboxDatetime % Test,
      scalacheck.scalacheck % Test,
      scalatest % Test,
      ovoEnergy.commsDockerKit % Test,
      ovoEnergy.commsMessagesTests % Test,
      wiremock % ServiceTest,
      ovoEnergy.commsTestHelpers % ServiceTest,
    ),

    scalafmtOnCompile := true,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.ovoenergy.comms.composer",

    dockerBaseImage := "openjdk:8-alpine",
    // TODO as we use ECR plugin this is not necessary anymore, the docker repository can be omitted
    dockerRepository := Some("852955754882.dkr.ecr.eu-west-1.amazonaws.com"),
    dockerUpdateLatest := true,

    javaOptions in Universal ++= Seq(
      "-Dcom.sun.management.jmxremote",
      "-Dcom.sun.management.jmxremote.port=9999",
      "-Dcom.sun.management.jmxremote.rmi.port=9999",
      "-Dcom.sun.management.jmxremote.local.only=false",
      "-Dcom.sun.management.jmxremote.authenticate=false",
      "-Dcom.sun.management.jmxremote.ssl=false"
    ),

    Ecr / region := Region.getRegion(Regions.EU_WEST_1),
    Ecr / repositoryName := (Docker / packageName).value,
    Ecr / repositoryTags ++= Seq(version.value),
    Ecr / localDockerImage := (Docker / dockerAlias).value.toString,
    Ecr / login := ((Ecr / login) dependsOn (Ecr / createRepository)).value,
    Ecr / push := ((Ecr / push) dependsOn (Docker / publishLocal, Ecr / login)).value,

    publishLocal := (publishLocal in Docker).value,
    publish := (Ecr / push).value,

    cloudFormationCapabilities := Seq(
      Capability.CAPABILITY_IAM
    ),

    Uat / cloudFormationStackParams := Map(
      "Environment" -> "uat",
      "Version" -> version.value
    ),

    Prd / cloudFormationStackParams := Map(
      "Environment" -> "prd",
      "Version" -> version.value
    )
  )
