package com.ovoenergy.comms.composer

import com.ovoenergy.comms.model.{Arbitraries => CoreArbitraries}

import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen._

import org.http4s.Uri
import model._
object Arbitraries extends CoreArbitraries {

  implicit val arbUri: Arbitrary[Uri] = Arbitrary(
    for {
      uuid <- Gen.uuid
    } yield Uri.unsafeFromString(s"/${uuid.toString}")
  )

  implicit val arbTemplateFragmentId: Arbitrary[TemplateFragmentId] = Arbitrary(
    for {
      string <- genNonEmptyString
    } yield TemplateFragmentId(string)
  )

  // TODO: Maybe we need to generate a valid template here ?
  implicit val arbTemplateFragment: Arbitrary[TemplateFragment] = Arbitrary(
    for {
      string <- genNonEmptyString
    } yield TemplateFragment(string)
  )

  implicit val arbRenderedFragment: Arbitrary[RenderedFragment] = Arbitrary(
    for {
      string <- genNonEmptyString
    } yield RenderedFragment(string)
  )

  implicit val arbRenderedPdfFragment: Arbitrary[RenderedPdfFragment] = Arbitrary(
    for {
      bytes <- arbitrary[Array[Byte]]
    } yield RenderedPdfFragment(bytes)
  )

  implicit val arbTemplateFragmentType: Arbitrary[TemplateFragmentType] = Arbitrary(
    oneOf(
      TemplateFragmentType.Email.Sender,
      TemplateFragmentType.Email.Subject,
      TemplateFragmentType.Email.HtmlBody,
      TemplateFragmentType.Email.TextBody,
      TemplateFragmentType.Sms.Body,
      TemplateFragmentType.Print.Body,
    )
  )

  def generate[A: Arbitrary]: A = {
    implicitly[Arbitrary[A]].arbitrary.apply(Gen.Parameters.default.withSize(3), Seed.random()).get
  }
}
