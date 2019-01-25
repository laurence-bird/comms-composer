import Dependencies._
import com.amazonaws.regions.{Region, Regions}

lazy val ServiceTest = config("servicetest") extend Test
lazy val IT = config("it") extend Test

lazy val awsJavaSdkVersion = "1.11.419"

lazy val composer = (project in file("."))
  .enablePlugins(BuildInfoPlugin, JavaServerAppPackaging, AshScriptPlugin, DockerPlugin, EcrPlugin)
  .configs(ServiceTest, IT)
  .settings(
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9"),

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
      "org.apache.kafka" % "kafka-clients" % "2.0.1",
    ),

    libraryDependencies ++= Seq(
      apache.avro,
      apache.kafkaClients,
      avro4sMacros,
      aws.javaSdkCore,
      fs2.core,
      fs2.kafkaClient,
      cats.core,
      cats.effect,
      cats.kernel,
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
      jCredstash,
      ovoEnergy.kafkaSerializationCore,
      ovoEnergy.kafkaSerializationCats,
      ovoEnergy.kafkaSerializationAvro,
      ovoEnergy.kafkaSerializationAvro4s,
      ovoEnergy.commsMessages,
      ovoEnergy.commsTemplates,
      ovoEnergy.commsAwsS3,
      ovoEnergy.commsAwsCommon,
      handlebars,
      s3Sdk,
      shapeless,
      jCredstash,
      logging.logbackClassic,
      logging.logbackCore,
      logging.logbackGelf,
      logging.logzIoLogbackAppender,
      logging.log4catsSlf4j,
      logging.log4catsNoop,
      logging.log4catsCore,
      logging.loggingLog4cats,
      logging.log4jOverSlf4j,
      logging.sl4jApi,
      scalaReflect,
      http4s.blazeClient % Test,
      scalacheck.shapeless % Test,
      scalacheck.toolboxDatetime % Test,
      scalacheck.scalacheck % Test,
      scalatest % Test,
      ovoEnergy.commsDockerKit % Test,
      ovoEnergy.commsMessagesTests % Test,
      wiremock % ServiceTest,
      ovoEnergy.commsTestHelpers % ServiceTest,
    ).map(_.exclude("log4j", "log4j")),

    scalafmtOnCompile := true,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.ovoenergy.comms.composer",

    dockerBaseImage := "openjdk:8-alpine",
    // TODO as we use ECR plugin this is not necessary anymore, the docker repository can be omitted
    dockerRepository := Some("852955754882.dkr.ecr.eu-west-1.amazonaws.com"),
    dockerUpdateLatest := true,

    Ecr / region := Region.getRegion(Regions.EU_WEST_1),
    Ecr / repositoryName := (Docker / packageName).value,
    Ecr / repositoryTags ++= Seq(version.value),
    Ecr / localDockerImage := (Docker / dockerAlias).value.toString,
    Ecr / login := ((Ecr / login) dependsOn (Ecr / createRepository)).value,
    Ecr / push := ((Ecr / push) dependsOn (Docker / publishLocal, Ecr / login)).value,

    publishLocal := (publishLocal in Docker).value,
    publish := (Ecr / push).value,

    stackTemplateFile := (templatesSourceFolder.value / "composer.yml"),
    stackRegion := "eu-west-1",
    stackCapabilities ++= Seq(
      "CAPABILITY_IAM"
    ),
    Staging / stackName := { stackName.value ++ "-" ++ "uat" },
    Staging / stackParams := Map(
      "Environment" -> "uat",
      "Version" -> version.value
    ),
    Staging / stackTags := Map(
      "Team" -> "comms",
      "Environment" -> "uat",
      "service" -> name.value
    ),
    Staging / deploy := deployOnConfiguration(Staging).value,
    Production / stackName := { stackName.value ++ "-" ++ "prd" },
    Production / stackParams := Map(
      "Environment" -> "prd",
      "Version" -> version.value
    ),
    Production / stackTags := Map(
      "Team" -> "comms",
      "Environment" -> "prd",
       "service" -> name.value
    ),
    Production / deploy := deployOnConfiguration(Production).value,
  )

val deploy = taskKey[Unit]("Deploy the service")

def deployOnConfiguration(s: Configuration): Def.Initialize[Task[Unit]] = {
  Def
    .sequential(
      Def.taskDyn {
        val stackExists = (s / stackDescribe).value.nonEmpty
        if (stackExists) {
          s / stackUpdate
        } else {
          s / stackCreate
        }
      },
      s / stackWait,
      Def.task {
        val log = streams.value.log
        (s / stackDescribe).value.fold(
          throw new RuntimeException("CloudFormation stack not found")
        )(
          stack =>
            if (!Set("UPDATE_COMPLETE", "CREATE_COMPLETE").contains(stack.getStackStatus))
              throw new RuntimeException(
                s"CloudFormation Deployment failed to complete ${stack.getStackStatus}")
            else
              log.info(
                s"Stack ${stack.getStackName} (${stack.getStackId}) create or updated correctly: ${stack.getStackStatus}")
        )
      }
    )
}

