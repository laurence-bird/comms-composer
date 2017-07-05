package com.ovoenergy.comms.composer

import cats.Semigroup
import cats.data.Validated
import com.ovoenergy.comms.model.ErrorCode

package object rendering {

  private[rendering] final case class Errors(missingKeys: Set[String],
                                             exceptions: Seq[Throwable],
                                             errorCode: ErrorCode) {
    def toErrorMessage: String = {
      val missingKeysMsg = {
        if (missingKeys.nonEmpty)
          s"""The template referenced the following non-existent keys:
             |${missingKeys.map(k => s" - $k").mkString("\n")}
           """.stripMargin
        else
          ""
      }
      val exceptionsMsg = {
        if (exceptions.nonEmpty)
          s"""The following exceptions were thrown:
             |${exceptions.map(e => s" - ${e.getMessage}").mkString("\n")}
           """.stripMargin
        else
          ""
      }
      s"$missingKeysMsg$exceptionsMsg"
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
