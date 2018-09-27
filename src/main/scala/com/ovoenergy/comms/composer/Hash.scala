package com.ovoenergy.comms.composer

import com.ovoenergy.comms.model._
import email.OrchestratedEmailV4
import print.OrchestratedPrintV2
import sms.OrchestratedSMSV3
import java.security.MessageDigest

import cats.Applicative
import cats.implicits._

trait Hash[F[_]] {
  def apply[A: Hashable](a: A): F[String]
}

object Hash {

  def apply[F[_]: Applicative]: Hash[F] = new Hash[F] {
    def apply[A: Hashable](a: A): F[String] = implicitly[Hashable[A]].hash(a).pure[F]
  }
}

trait Hashable[A] {
  def hash(a: A): String
}

object Hashable {

  implicit val hashableSms: Hashable[OrchestratedSMSV3] = new Hashable[OrchestratedSMSV3]() {
    def hash(a: OrchestratedSMSV3): String = {
      val messageDigest = MessageDigest.getInstance("MD5")
      val commHash = messageDigest.digest(
        (
          a.metadata.deliverTo,
          a.templateData,
          a.metadata.templateManifest
        ).toString.getBytes)

      new String(commHash)
    }
  }

  implicit val hashableEmail: Hashable[OrchestratedEmailV4] = new Hashable[OrchestratedEmailV4] {
    def hash(a: OrchestratedEmailV4): String = {
      val messageDigest = MessageDigest.getInstance("MD5")
      val commHash = messageDigest.digest(
        (
          a.metadata.deliverTo,
          a.templateData,
          a.metadata.templateManifest
        ).toString.getBytes)

      new String(commHash)
    }
  }

  implicit val hashablePrint: Hashable[OrchestratedPrintV2] = new Hashable[OrchestratedPrintV2] {
    def hash(a: OrchestratedPrintV2): String = {
      val messageDigest = MessageDigest.getInstance("MD5")
      val commHash = messageDigest.digest(
        (
          a.customerProfile,
          a.address,
          a.templateData,
          a.metadata.templateManifest
        ).toString.getBytes)

      new String(commHash)
    }
  }

  implicit val hashableString: Hashable[String] = new Hashable[String] {
    def hash(a: String): String = {
      val messageDigest = MessageDigest.getInstance("MD5")
      val strHash = messageDigest.digest(a.getBytes)
      new String(strHash)
    }
  }
}
