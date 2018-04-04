package com.ovoenergy.comms.composer

import cats.Semigroup
import cats.data.Validated
import com.ovoenergy.comms.model.ErrorCode

package object rendering {

  case class FailedToRender(reason: String, errorCode: ErrorCode)

  private[rendering] final case class Errors(missingKeys: Set[String],
                                             exceptions: Seq[Throwable],
                                             errorCode: ErrorCode) {
    def toErrorMessage: String = {
      val missingKeysMsg = {
        if (missingKeys.nonEmpty)
          List(s"""The template referenced the following non-existent keys: ${missingKeys.mkString("[", ",", "]")}""")
        else
          List.empty[String]
      }
      val exceptionsMsg = {
        if (exceptions.nonEmpty)
          List(
            s"""The following exceptions were thrown: ${exceptions.map(e => e.getMessage).mkString("[", ",", "]")}""")
        else
          List.empty[String]
      }

      (missingKeysMsg ++ exceptionsMsg).mkString(". ")
    }
  }

  private[rendering] object Errors {
    implicit val semigroup: Semigroup[Errors] = new Semigroup[Errors] {
      override def combine(x: Errors, y: Errors): Errors =
        Errors(x.missingKeys ++ y.missingKeys, x.exceptions ++ y.exceptions, x.errorCode)
    }
  }

  private[rendering] type ErrorsOr[A] = Validated[Errors, A]

}
