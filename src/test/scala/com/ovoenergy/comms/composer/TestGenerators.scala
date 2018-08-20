package com.ovoenergy.comms.composer

import com.ovoenergy.comms.model.Arbitraries
import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}

trait TestGenerators { arbs: Arbitraries =>
  def generate[A: Arbitrary] = {
    implicitly[Arbitrary[A]].arbitrary.apply(Gen.Parameters.default.withSize(3), Seed.random()).get
  }
}
