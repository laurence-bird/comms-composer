package com.ovoenergy.comms.composer

import org.scalatest._
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.concurrent.ScaledTimeSpans

class UnitSpec
    extends FlatSpec
    with Matchers
    with EitherValues
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
