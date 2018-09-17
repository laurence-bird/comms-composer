package com.ovoenergy.comms.composer.http

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration}

object Retry {

  implicit class EitherExtensions[A, B](retryResult: Either[Failed[A], Succeeded[B]]) {
    def flattenRetry: Either[A, B] = {
      retryResult match {
        case Left(Failed(attempts, finalFailure)) => Left(finalFailure)
        case Right(Succeeded(result, attempts))   => Right(result)
      }
    }
  }

  case class Failed[A](attemptsMade: Int, finalFailure: A)

  case class Succeeded[A](result: A, attempts: Int)

  /**
    * @param attempts The total number of attempts to make, including both the first attempt and any retries.
    * @param backoff  Sleep between attempts. The number of attempts made so far is passed as an argument.
    */
  def constantDelayFunc(interval: FiniteDuration) = (_: Int) => interval

  val retryImmediately = (_: Int) => FiniteDuration.apply(0, TimeUnit.MICROSECONDS)

  case class ConstantDelayRetry(attempts: Int, interval: FiniteDuration)

  case class RetryConfig(attempts: Int, backoff: Int => FiniteDuration)

  def constantDelay(retry: ConstantDelayRetry) = {
    RetryConfig(retry.attempts, constantDelayFunc(retry.interval))
  }

  /**
    * Attempt to perform an operation up to a given number of times, then give up.
    *
    * @param onFailure A hook that is called after each failure. Useful for logging.
    * @param f         The operation to perform.
    */
  def retry[A, B](config: RetryConfig, onFailure: A => Unit, shouldRetry: A => Boolean)(
      f: => Either[A, B]): Either[Failed[A], Succeeded[B]] = {
    @tailrec
    def rec(attempt: Int): Either[Failed[A], Succeeded[B]] = {
      f match {
        case Right(result) => Right(Succeeded(result, attempt))
        case Left(failure) =>
          onFailure(failure)
          if (attempt == config.attempts | !shouldRetry(failure)) {
            Left(Failed(attempt, failure))
          } else {
            Thread.sleep(config.backoff(attempt).toMillis)
            rec(attempt + 1)
          }
      }
    }

    rec(1)
  }
}
