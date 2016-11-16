import DockerPackage._

name := "composer"
organization := "com.ovoenergy"

scalaVersion := "2.11.8"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// Make ScalaTest write test reports that CircleCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", testReportsDir)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.12",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.14",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "io.logz.logback" % "logzio-logback-appender" % "1.0.11",
  "org.scalatest" %% "scalatest" % "3.0.1" % Test
)

lazy val root = (project in file("."))
  .withDocker


