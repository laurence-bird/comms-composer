import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.JavaServerAppPackaging
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import com.typesafe.sbt.packager.docker._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import sbt.Keys._
import sbt._
import com.ovoenergy.sbt.credstash.CredstashPlugin.autoImport._
import scala.language.postfixOps

object DockerPackage {

  lazy val dockerLoginTask = TaskKey[Unit]("dockerLogin", "Log in to Amazon ECR")
  lazy val awsAccountNumber = sys.env.getOrElse("AWS_ACCOUNT_ID", "NOT_SET")

  private lazy val setupAlpine = Seq(
    Cmd("RUN", "apk --update add openjdk8-jre"),
    Cmd("RUN", "apk --update add bash")
  )

  private lazy val settings = Seq(
    packageName in Docker := "composer",
    dockerRepository := Some(s"$awsAccountNumber.dkr.ecr.eu-west-1.amazonaws.com"),
    dockerUpdateLatest := false,
    dockerExposedPorts := Seq(8080),
    dockerBaseImage := "alpine",
    dockerCommands := dockerCommands.value.head +: setupAlpine ++: dockerCommands.value.tail,
    mappings in Universal += file("src/main/resources/application.conf")  -> "conf/local/application.conf",
    mappings in Universal += file("src/main/resources/logback.xml")       -> "conf/local/logback.xml",
    mappings in Universal += file("target/credstash/uat.conf")            -> "conf/uat/application.conf",
    mappings in Universal += file("target/credstash/prd.conf")            -> "conf/prd/application.conf",
    mappings in Universal += file("target/credstash/uat-logback.xml")     -> "conf/uat/logback.xml",
    mappings in Universal += file("target/credstash/prd-logback.xml")     -> "conf/prd/logback.xml",
    bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/${ENV,,}/application.conf"""",
    bashScriptExtraDefines += """addJava "-Dlogback.configurationFile=${app_home}/../conf/${ENV,,}/logback.xml"""",
    bashScriptExtraDefines += """addJava "-Xms256M"""",
    bashScriptExtraDefines += """addJava "-Xmx256M""""
  )

  implicit class DockerProject(project: Project) {
    def withDocker: Project = project
      .settings(settings: _*)
      .enablePlugins(JavaServerAppPackaging, DockerPlugin)
      .settings(
        dockerLoginTask := {
          import sys.process._
          "aws --region eu-west-1 ecr get-login" #| "bash" !
        },
        (publishLocal in Docker) := (publishLocal in Docker).dependsOn(credstashPopulateConfig).value,
        (publish in Docker) := (publish in Docker).dependsOn(dockerLoginTask).value
      )
  }
}
