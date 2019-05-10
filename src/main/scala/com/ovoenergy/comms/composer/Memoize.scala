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

  /*
   * We keep a Map of key an Deferred. The Deferred is completed when the entry
   * for that key has been calculated.
   *
   * The map is kept inside a Ref and updated each time a new key is requested.
   *
   * We create a Deferred each call, even if it is not used, as the ref modify
   * must be pure.
   *
   * Each cal we estract the map from the ref, check if it has the entry, then:
   *
   * If does not have the entry, it will add the deferred to the map, then will
   * call the effect to create the entry and flatMap on it to complete the
   * deferred.
   *
   * It will return the deferred
   *
   */

  // TODO Use partially applied
  def apply[F[_]: Concurrent, Key, A](f: Key => F[A]): F[Memoize[F, Key, A]] = {

    type Result = Either[Throwable, A]

    Ref[F].of(Map.empty[Key, Deferred[F, Result]]).map { state =>
      new Memoize[F, Key, A] {

        def get(id: Key): F[A] = {

          Deferred[F, Result].flatMap { deferred =>
            state
              .modify { hashMap =>
                val completeDeferred = f(id).attempt
                  .flatTap(deferred.complete)
                  .guaranteeCase {
                    case ExitCase.Canceled =>
                      deferred.complete(Left(UpdateInterruptedException))
                    case _ =>
                      ().pure[F]
                  }

                val entry = hashMap.get(id)

                entry.fold((hashMap + (id -> deferred), completeDeferred))(d => (hashMap, d.get))
              }
              .flatten
              .rethrow
          }
        }
      }
    }
  }
}
