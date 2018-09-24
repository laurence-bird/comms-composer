package com.ovoenergy.comms.composer

import cats.effect.IO
import org.scalatest.concurrent.Futures
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

abstract class IntegrationSpec extends WordSpec with Matchers with Futures {

  implicit val patience: PatienceConfig = PatienceConfig(scaled(5.seconds), 500.millis)

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
