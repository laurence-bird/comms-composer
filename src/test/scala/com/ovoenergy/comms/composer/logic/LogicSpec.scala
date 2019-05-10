package com.ovoenergy.comms.composer
package logic

import java.{util => ju}

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.implicits._

import org.http4s.Uri

import org.scalacheck._
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen._

import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.model.TemplateData

import model._
import rendering._
import mocks._

import Arbitraries._
import com.ovoenergy.comms.model.TemplateManifest
import java.time.Instant

class LogicSpec extends UnitSpec {

  behavior of "templateFragmentIdFor"

  it should "handle Email sender" in forAll { manifest: TemplateManifest =>
    templateFragmentIdFor(manifest, TemplateFragmentType.Email.Sender) shouldBe TemplateFragmentId(
      s"${manifest.id}/${manifest.version}/email/sender.txt")
  }

  it should "handle Email subject" in forAll { manifest: TemplateManifest =>
    templateFragmentIdFor(manifest, TemplateFragmentType.Email.Subject) shouldBe TemplateFragmentId(
      s"${manifest.id}/${manifest.version}/email/subject.txt")
  }

  it should "handle Email html body" in forAll { manifest: TemplateManifest =>
    templateFragmentIdFor(manifest, TemplateFragmentType.Email.HtmlBody) shouldBe TemplateFragmentId(
      s"${manifest.id}/${manifest.version}/email/body.html")
  }

  it should "handle Email text body" in forAll { manifest: TemplateManifest =>
    templateFragmentIdFor(manifest, TemplateFragmentType.Email.TextBody) shouldBe TemplateFragmentId(
      s"${manifest.id}/${manifest.version}/email/body.txt")
  }

  it should "handle Print body" in forAll { manifest: TemplateManifest =>
    templateFragmentIdFor(manifest, TemplateFragmentType.Print.Body) shouldBe TemplateFragmentId(
      s"${manifest.id}/${manifest.version}/print/body.html")
  }

  it should "handle SMS body" in forAll { manifest: TemplateManifest =>
    templateFragmentIdFor(manifest, TemplateFragmentType.Sms.Body) shouldBe TemplateFragmentId(
      s"${manifest.id}/${manifest.version}/sms/body.txt")
  }

  behavior of "TemplateData Semigroup"

  it should "override any left with right string" in forAll { (loser: TemplateData, str: String) =>
    val winner = TemplateData.fromString(str)
    Semigroup[TemplateData].combine(loser, winner) shouldBe winner
  }

  it should "override any left with right list" in forAll {
    (loser: TemplateData, str1: String, str2: String) =>
      val winner =
        TemplateData.fromSeq(List(TemplateData.fromString(str1), TemplateData.fromString(str2)))
      Semigroup[TemplateData].combine(loser, winner) shouldBe winner
  }

  it should "override string left with right map" in forAll {
    (str: String, map: Map[String, TemplateData]) =>
      val winner = TemplateData.fromMap(map)
      val loser = TemplateData.fromString(str)

      Semigroup[TemplateData].combine(loser, winner) shouldBe winner
  }

  it should "override list left with right map" in forAll {
    (str1: String, str2: String, map: Map[String, TemplateData]) =>
      val winner = TemplateData.fromMap(map)
      val loser =
        TemplateData.fromSeq(List(TemplateData.fromString(str1), TemplateData.fromString(str2)))
      Semigroup[TemplateData].combine(loser, winner) shouldBe winner
  }

  it should "deep merge two maps" in forAll { (str1: String, str2: String, str3: String) =>
    val l = TemplateData.fromMap(
      Map(str1 -> TemplateData.fromString(str1), str2 -> TemplateData.fromString(str2)))
    val r = TemplateData.fromMap(
      Map(str2 -> TemplateData.fromString(str2), str3 -> TemplateData.fromString(str3)))

    val expected = TemplateData.fromMap(
      Map(
        str1 -> TemplateData.fromString(str1),
        str2 -> TemplateData.fromString(str2),
        str3 -> TemplateData.fromString(str3)
      ))

    Semigroup[TemplateData].combine(l, r) shouldBe expected
  }

  it should "deep merge two maps overriding first map values from the second map " in forAll {
    (str1: String, str21: String, str22: String, str3: String) =>
      val l = TemplateData.fromMap(
        Map(str1 -> TemplateData.fromString(str1), str21 -> TemplateData.fromString(str21)))
      val r = TemplateData.fromMap(
        Map(str21 -> TemplateData.fromString(str22), str3 -> TemplateData.fromString(str3)))

      val expected = TemplateData.fromMap(
        Map(
          str1 -> TemplateData.fromString(str1),
          str21 -> TemplateData.fromString(str22),
          str3 -> TemplateData.fromString(str3)
        ))

      Semigroup[TemplateData].combine(l, r) shouldBe expected
  }
}
