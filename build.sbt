import DockerPackage._

name := "composer"
organization := "com.ovoenergy"

scalaVersion := "2.11.8"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// Make ScalaTest write test reports that CircleCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", testReportsDir)

resolvers += Resolver.bintrayRepo("ovotech", "maven")
libraryDependencies ++= Seq(
  "com.ovoenergy" %% "comms-kafka-messages-internal" % "0.0.7-SNAPSHOT",
  "com.github.jknack" % "handlebars" % "4.0.6",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.12",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.14",
  "org.typelevel" %% "cats-free" % "0.8.1",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "io.logz.logback" % "logzio-logback-appender" % "1.0.11",
  "org.scalatest" %% "scalatest" % "3.0.1" % Test
)

scalafmtConfig in ThisBuild := Some(file(".scalafmt.conf"))
reformatOnCompileSettings

lazy val root = (project in file(".")).withDocker
