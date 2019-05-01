package com.ovoenergy.comms.composer

import org.scalatest.{FlatSpec, Matchers, concurrent, prop}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import concurrent.ScaledTimeSpans

class UnitSpec
    extends FlatSpec
    with Matchers
    with ScalaCheckDrivenPropertyChecks
    with ScaledTimeSpans
    with IOFutures {

  override def spanScaleFactor: Double = {
    sys.env
      .get("TEST_TIME_SCALE_FACTOR")
      .map(_.toDouble)
      .getOrElse(super.spanScaleFactor)
  }

}
