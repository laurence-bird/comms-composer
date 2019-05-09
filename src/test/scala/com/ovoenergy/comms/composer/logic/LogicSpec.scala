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

}
