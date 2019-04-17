package com.ovoenergy.comms.composer

import java.util.concurrent._
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.compat.java8.DurationConverters._

import cats._, implicits._
import cats.effect.{Sync, Resource}

import io.chrisdavenport.cats.time._

import io.micrometer.datadog._
import io.micrometer.core.{instrument => micrometer}
import io.micrometer.core.instrument.{Clock, MeterRegistry, Tag}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

package object metrics {

  def createMeterRegistry[F[_]: Sync](c: Config.Metrics): Resource[F, MeterRegistry] = {

    val datadogConfig: DatadogConfig = new DatadogConfig {
      override val apiKey = c.apiKey
      override val applicationKey = c.applicationKey
      override val enabled = true
      override val step = java.time.Duration.ofSeconds(c.rate.toSeconds.toInt)
      override val uri = c.endpoint.toString
      // The parent of DatadogConfig need this abstract method to return null
      // to apply the default value
      def get(id: String): String = null
    }

    if (c.enabled) {
      Resource
        .make(
          Sync[F].delay(
            DatadogMeterRegistry
              .builder(datadogConfig)
              .build
          )
        )(toStop => Sync[F].delay(toStop.stop))
        .widen[MeterRegistry]
    } else {
      Resource.pure[F, MeterRegistry](new SimpleMeterRegistry)
    }
  }
}
