package com.ovoenergy.comms

import org.scalatest._

class FooSpec extends FlatSpec with Matchers {

  "splines" should "be reticulated" in {
    Foo.bar should be("reticulating splines")
  }

}
