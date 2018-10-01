package com.ovoenergy.comms.composer

import cats.syntax.monadError._
import cats.effect.IO
import fs2.Stream.ToEffect

import scala.util.{Success, Failure}
import org.scalatest.concurrent.Futures

import scala.language.implicitConversions

object IOFutures {
  def lastOrRethrow[O](te: ToEffect[IO, O]): IO[O] = {
    te.last
      .map(_.toRight[Throwable](new IllegalStateException("Empty Stream")))
      .rethrow
  }
}

trait IOFutures extends Futures {

  implicit class RichToEffectIO[O](te: ToEffect[IO, O]) {
    def lastOrRethrow: IO[O] = IOFutures.lastOrRethrow(te)
  }

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
