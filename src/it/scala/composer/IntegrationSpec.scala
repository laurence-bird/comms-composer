package com.ovoenergy.comms.composer

import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration._

abstract class IntegrationSpec extends WordSpec with Matchers with IOFutures {

  sys.props.put("logback.configurationFile","logback-it.xml")

  implicit val patience: PatienceConfig = PatienceConfig(scaled(5.seconds), 500.millis)

}
