package com.ovoenergy.comms.composer

import io.chrisdavenport.log4cats.StructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import cats.effect.IO

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

  implicit val unsafeLogger: StructuredLogger[IO] = Slf4jLogger.unsafeCreate[IO]

  override def spanScaleFactor: Double = {
    sys.env
      .get("TEST_TIME_SCALE_FACTOR")
      .map(_.toDouble)
      .getOrElse(super.spanScaleFactor)
  }

}
