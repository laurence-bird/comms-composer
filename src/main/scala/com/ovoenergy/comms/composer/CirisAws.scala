package com.ovoenergy.comms.composer

import com.amazonaws.regions.DefaultAwsRegionProviderChain
import ciris._
import ciris.api._

import scala.util.Try

object CirisAws {

  sealed trait AwsKey
  case object AwsRegion extends AwsKey

  val AwsKeyType: ConfigKeyType[AwsKey] = ConfigKeyType[AwsKey]("AWS")

  val AwsConfigSource: ConfigSource[Id, AwsKey, String] = ConfigSource(AwsKeyType) {
    case AwsRegion =>
      Try(new DefaultAwsRegionProviderChain().getRegion).fold(
        t => Left(ConfigError(t.getMessage)),
        ok => Right(ok)
      )
  }

  def aws[Value](key: AwsKey)(
      implicit decoder: ConfigDecoder[String, Value]
  ): ConfigEntry[Id, AwsKey, String, Value] = {
    AwsConfigSource
      .read(AwsRegion)
      .decodeValue[Value]
  }

  def awsF[F[_]: Sync, Value](key: AwsKey)(
      implicit decoder: ConfigDecoder[String, Value]
  ): ConfigEntry[F, AwsKey, String, Value] = {
    AwsConfigSource
      .suspendF[F]
      .read(AwsRegion)
      .decodeValue[Value]
  }

}
