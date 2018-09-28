package com.ovoenergy.comms.composer
import cats.effect.IO

import scala.util.{Success, Failure}
import org.scalatest.concurrent.Futures

trait IOFutures extends Futures {

  implicit def convertIO[T](io: IO[T]): FutureConcept[T] =
    new FutureConcept[T] {

      private val futureFromIo = io.unsafeToFuture()

      def eitherValue: Option[Either[Throwable, T]] =
        futureFromIo.value.map {
          case Success(o) => Right(o)
          case Failure(e) => Left(e)
        }
      def isExpired: Boolean =
        false // Scala Futures themselves don't support the notion of a timeout
      def isCanceled: Boolean =
        false // Scala Futures don't seem to be cancelable either
    }

}
