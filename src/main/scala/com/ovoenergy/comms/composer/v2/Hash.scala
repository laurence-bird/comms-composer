package com.ovoenergy.comms.composer.v2

import java.security.MessageDigest

import cats.Monad
import com.ovoenergy.comms.model.email.OrchestratedEmailV4
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model.sms.OrchestratedSMSV3
import cats.implicits._

trait Hash[F[_]] {
  def apply[A: Hashable](a: A): F[String]
}

object Hash {

  def apply[F[_]: Monad](): Hash[F] = new Hash[F] {
    def apply[A: Hashable](a: A): F[String] = implicitly[Hashable[A]].hash(a).pure[F]
  }
}

trait Hashable[A] {
  def hash(a: A): String
}

object Hashable {

  val algorithm = "MD5"

  implicit val HashableSms: Hashable[OrchestratedSMSV3] = new Hashable[OrchestratedSMSV3]() {
    def hash(a: OrchestratedSMSV3): String = {
      val commHash = MessageDigest
        .getInstance(algorithm)
        .digest((a.metadata.deliverTo, a.templateData, a.metadata.templateManifest).toString.getBytes)

      new String(commHash)
    }
  }

  implicit val HashableEmail: Hashable[OrchestratedEmailV4] = new Hashable[OrchestratedEmailV4] {
    def hash(a: OrchestratedEmailV4): String = {
      val commHash = MessageDigest
        .getInstance(algorithm)
        .digest((a.metadata.deliverTo, a.templateData, a.metadata.templateManifest).toString.getBytes)

      new String(commHash)
    }
  }

  implicit val HashablePrint: Hashable[OrchestratedPrintV2] = new Hashable[OrchestratedPrintV2] {
    def hash(a: OrchestratedPrintV2): String = {
      val commHash = MessageDigest
        .getInstance(algorithm)
        .digest((a.customerProfile, a.address, a.templateData, a.metadata.templateManifest).toString.getBytes)

      new String(commHash)
    }
  }

  implicit val HashableString: Hashable[String] = new Hashable[String] {
    def hash(a: String): String = {
      val strHash = MessageDigest
        .getInstance(algorithm)
        .digest(a.getBytes)

      new String(strHash)
    }
  }
}
