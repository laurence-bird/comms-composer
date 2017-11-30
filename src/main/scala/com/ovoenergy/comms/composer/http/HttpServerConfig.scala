package com.ovoenergy.comms.composer.http

import com.typesafe.config.Config
import pureconfig._
import pureconfig.error.ConfigReaderFailures

case class HttpServerConfig(host: String, port: Int)

object HttpServerConfig {

  def fromConfig(config: Config): Either[ConfigReaderFailures, HttpServerConfig] = loadConfig[HttpServerConfig](config)

  def unsafeFromConfig(config: Config): HttpServerConfig =
    fromConfig(config)
      .fold(error => throw new RuntimeException(s"""Error parsing the config: ${error.toList.mkString(",")}"""),
            identity)
}
