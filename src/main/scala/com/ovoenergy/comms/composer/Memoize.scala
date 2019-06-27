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

  /**
    * The `f` computation is executed only once, when the item is requested for the first time
    * Every subsequent call gets the cached result.
    * The initial execution of `f` is not cancelable. Look at the
    * implementation of cats.effect.Concurrent.memoize for a strategy to
    * enable cancelation if desired.
    */
  def apply[F[_]: Concurrent, Key, A](f: Key => F[A]): F[Memoize[F, Key, A]] =
    Ref[F].of(Map.empty[Key, Deferred[F, Either[Throwable, A]]]).map { state =>
      new Memoize[F, Key, A] {
        def get(id: Key): F[A] =
          Deferred[F, Either[Throwable, A]].flatMap { deferred =>
            state
              .modify { hashMap =>
                val completeDeferred = f(id).attempt
                  .flatTap(deferred.complete)
                  .uncancelable

                hashMap.get(id) match {
                  case None => (hashMap + (id -> deferred), completeDeferred)
                  case Some(d) => (hashMap, d.get)
                }
              }
              .flatten
              .rethrow
          }
      }
    }
}
