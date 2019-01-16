package com.ovoenergy.comms.composer

import org.scalatest.{FlatSpec, Matchers, concurrent, prop}
import concurrent.ScaledTimeSpans
import prop.GeneratorDrivenPropertyChecks

import com.ovoenergy.comms.model.Arbitraries

class UnitSpec
    extends FlatSpec
    with Matchers
    with GeneratorDrivenPropertyChecks
    with Arbitraries
    with ScaledTimeSpans
    with IOFutures {

  override def spanScaleFactor: Double = {
    sys.env
      .get("TEST_TIME_SCALE_FACTOR")
      .map(_.toDouble)
      .getOrElse(super.spanScaleFactor)
  }

}
