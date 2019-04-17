package com.ovoenergy.comms.composer
package metrics

import java.util.concurrent._
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.compat.java8.DurationConverters._

import cats._, implicits._
import cats.effect.{Sync, Resource}

import io.chrisdavenport.cats.time._

import io.micrometer.datadog._
import io.micrometer.core.{instrument => micrometer}
import io.micrometer.core.instrument.{MeterRegistry, Tag}

import Reporter.{Counter, Timer}

trait Reporter[F[_]] {
  def counter(name: String, tags: Map[String, String] = Map.empty): F[Counter[F]]
  def timer(name: String, tags: Map[String, String] = Map.empty): F[Timer[F]]
}

object Reporter {
  trait Counter[F[_]] {
    def increment: F[Unit]
  }

  trait Timer[F[_]] {
    def record(d: FiniteDuration): F[Unit]
    def record(d: java.time.Duration): F[Unit] = record(d.toScala)
  }

  def apply[F[_]](implicit ev: Reporter[F]): Reporter[F] = ev

  def create[F[_]: Sync](c: Config.Metrics): Resource[F, Reporter[F]] = {
    createMeterRegistry[F](c).map(registry => Reporter.fromRegistry(registry, c))
  }

  def fromRegistry[F[_]](mx: MeterRegistry, config: Config.Metrics)(
      implicit F: Sync[F]): Reporter[F] =
    new Reporter[F] {
      // local tags overwrite global tags
      def effectiveTags(tags: Map[String, String]) =
        (config.tags ++ tags).map { case (k, v) => Tag.of(k, v) }.asJava

      def counter(name: String, tags: Map[String, String]): F[Counter[F]] =
        F.delay {
            micrometer.Counter
              .builder(s"${config.prefix}${name}")
              .tags(effectiveTags(tags))
              .register(mx)
          }
          .map { c =>
            new Counter[F] {
              def increment = F.delay(c.increment)
            }
          }

      def timer(name: String, tags: Map[String, String]): F[Timer[F]] =
        F.delay {
            micrometer.Timer
              .builder(s"${config.prefix}${name}")
              .tags(effectiveTags(tags))
              .register(mx)
          }
          .map { t =>
            new Timer[F] {
              def record(d: FiniteDuration) = F.delay(t.record(d.toMillis, MILLISECONDS))
            }
          }

    }
}
