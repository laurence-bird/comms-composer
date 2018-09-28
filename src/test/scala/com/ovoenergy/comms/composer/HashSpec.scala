package com.ovoenergy.comms.composer

import java.security.MessageDigest

import cats.effect.IO
import com.ovoenergy.comms.model.Arbitraries
import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.sms.OrchestratedSMSV3
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

class HashSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks with Arbitraries {

  val algorithm = "MD5"
  val hash: Hash[IO] = Hash[IO]

  it should "hash OrchestratedSMSV3" in forAll { sms: OrchestratedSMSV3 =>
    hash(sms).unsafeRunSync() shouldBe new String(
      MessageDigest
        .getInstance(algorithm)
        .digest((sms.metadata.deliverTo, sms.templateData, sms.metadata.templateManifest)
          .toString()
          .getBytes))
  }

  it should "hash OrchestratedEmailV4" in forAll { email: OrchestratedEmailV4 =>
    hash(email).unsafeRunSync() shouldBe new String(
      MessageDigest
        .getInstance(algorithm)
        .digest((email.metadata.deliverTo, email.templateData, email.metadata.templateManifest)
          .toString()
          .getBytes))
  }

  it should "hash OrchestratedPrintV2" in forAll { print: OrchestratedPrintV2 =>
    hash(print).unsafeRunSync() shouldBe new String(MessageDigest
      .getInstance(algorithm)
      .digest(
        (print.customerProfile, print.address, print.templateData, print.metadata.templateManifest)
          .toString()
          .getBytes))
  }

  it should "hash String" in forAll { str: String =>
    hash(str).unsafeRunSync() shouldBe new String(
      MessageDigest
        .getInstance(algorithm)
        .digest(str.getBytes))
  }

}
