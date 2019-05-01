package com.ovoenergy.comms.composer

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

import cats.effect._

import scala.util.{Success, Failure}
import org.scalatest.concurrent.Futures

trait IOFutures extends Futures {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)

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
