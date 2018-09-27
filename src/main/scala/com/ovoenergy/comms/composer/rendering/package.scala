package com.ovoenergy.comms.composer

import model._
import cats.Semigroup
import com.ovoenergy.comms.model.ErrorCode

package object rendering {

  final case class Errors(
      missingKeys: Set[String],
      exceptions: Seq[Throwable],
      errorCode: ErrorCode) {
    def toErrorMessage: String = {
      val missingKeysMsg = {
        if (missingKeys.nonEmpty)
          List(s"""The template referenced the following non-existent keys: ${missingKeys
            .mkString("[", ",", "]")}""")
        else
          List.empty[String]
      }
      val exceptionsMsg = {
        if (exceptions.nonEmpty)
          List(s"""The following exceptions were thrown: ${exceptions
            .map(e => e.getMessage)
            .mkString("[", ",", "]")}""")
        else
          List.empty[String]
      }

      (missingKeysMsg ++ exceptionsMsg).mkString(". ")
    }
    def toComposerError: Throwable = {
      ComposerError(toErrorMessage, errorCode)
    }
  }

  object Errors {
    // TODO: We shouldn't discard the errorCode here, should be a Nel[ErrorCode] ?
    implicit val semigroup: Semigroup[Errors] = new Semigroup[Errors] {
      override def combine(x: Errors, y: Errors): Errors =
        Errors(x.missingKeys ++ y.missingKeys, x.exceptions ++ y.exceptions, x.errorCode)
    }
  }

}
