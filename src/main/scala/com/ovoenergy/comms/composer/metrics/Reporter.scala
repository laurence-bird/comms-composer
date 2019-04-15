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

  def create[F[_]: Sync](c: Config.Datadog): Resource[F, Reporter[F]] = {
    val tf = new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r)
        t.setDaemon(true)
        t
      }
    }

    def datadogConfig(c: Config.Datadog): DatadogConfig =
      new DatadogConfig {
        override val apiKey = c.apiKey
        override val applicationKey = c.applicationKey
        override val enabled = true
        override val step = java.time.Duration.ofSeconds(c.rate.toSeconds.toInt)
        override val uri = c.endpoint.toString
        // The parent of DatadogConfig need this abstract method to return null
        // to apply the default value
        def get(id: String): String = null
      }

    for {
      registry <- Resource.make(
        Sync[F].delay(
          DatadogMeterRegistry
            .builder(datadogConfig(c))
            .build))(toStop => Sync[F].delay(toStop.stop))
    } yield fromRegistry[F](registry, c)
  }

  def fromRegistry[F[_]](mx: MeterRegistry, config: Config.Datadog)(
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
