package com.ovoenergy.comms.composer

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._

trait Memoize[F[_], Key, A] {
  def get(id: Key): F[A]
}

object Memoize {

  case object UpdateInterruptedException extends Exception

  // TODO Use partially applied
  def apply[F[_]: Concurrent, Key, A](f: Key => F[A]): F[Memoize[F, Key, A]] = {

    type Result = Either[Throwable, A]

    Ref[F].of(Map.empty[Key, Deferred[F, Result]]).map { state =>
      new Memoize[F, Key, A] {

        def get(id: Key): F[A] = {
          Deferred[F, Result].flatMap { deferred =>
            state
              .modify { xs =>
                val handleEmpty = (
                  xs + (id -> deferred),
                  f(id).attempt
                    .flatTap(deferred.complete)
                    .guaranteeCase {
                      case ExitCase.Canceled =>
                        deferred.complete(Left(UpdateInterruptedException))
                      case _ =>
                        ().pure[F]
                    }
                )

                xs.get(id).fold(handleEmpty)(d => (xs, d.get))
              }
              .flatten
              .rethrow
          }
        }
      }
    }
  }
}
