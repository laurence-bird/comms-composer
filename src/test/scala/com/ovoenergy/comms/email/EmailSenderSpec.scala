package com.ovoenergy.comms.email

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import org.scalatest.{FlatSpec, Matchers}

class EmailSenderSpec extends FlatSpec with Matchers {

  it should "accept a valid name-address pair" in {
    EmailSender.parse("Chris Birchall <chris.birchall@ovoenergy.com>") should be(
      Valid(EmailSender("Chris Birchall", "chris.birchall@ovoenergy.com")))
  }

  it should "reject a name-only string" in {
    EmailSender.parse("Chris Birchall") shouldBe an[Invalid[NonEmptyList[String]]]
  }

  it should "reject an address-only string" in {
    EmailSender.parse("<chris.birchall@ovoenergy.com>") shouldBe an[Invalid[NonEmptyList[String]]]
  }

}
