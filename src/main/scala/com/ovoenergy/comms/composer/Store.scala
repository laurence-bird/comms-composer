package com.ovoenergy.comms.composer

import model._
import com.ovoenergy.comms.aws._
import common.CredentialsProvider
import common.model._
import s3.S3
import s3.model._
import java.util.UUID

import cats.implicits._
import cats.effect.{ConcurrentEffect, Sync}
import fs2._
import org.http4s.Uri
import org.http4s.client._
import org.http4s.client.blaze._

import scala.concurrent.ExecutionContext

trait Store[F[_]] {
  def upload[A: Fragment](commId: CommId, traceToken: TraceToken, fragment: A): F[Uri]
}

object Store {

  case class Config(
      // TODO rename to bucket
      bucketName: Bucket,
      region: Region = Region.`eu-west-1`,
      s3Endpoint: Option[Uri] = None
  )

  trait Keys[F[_]] {
    def get(commId: CommId, traceToken: TraceToken): F[Key]
  }

  class RandomSuffixKeys[F[_]: Sync] extends Keys[F] {
    override def get(commId: CommId, traceToken: TraceToken): F[Key] = {
      Sync[F].delay(Key(s"${commId}/${UUID.randomUUID()}"))
    }
  }

  def stream[F[_]: ConcurrentEffect](config: Config, ec: ExecutionContext): Stream[F, Store[F]] = {
    BlazeClientBuilder[F](ec).stream
      .map(httpClient => fromHttpClient(httpClient, config, new RandomSuffixKeys[F]))
  }

  def fromHttpClient[F[_]: Sync](httpclient: Client[F], config: Config, keys: Keys[F]): Store[F] =
    apply[F](S3[F](httpclient, CredentialsProvider.default[F], config.region), config, keys)

  def apply[F[_]: Sync](s3: S3[F], config: Config, keys: Keys[F]): Store[F] = new Store[F] {

    private val region = config.region
    private val bucketName = config.bucketName

    override def upload[A](commId: CommId, traceToken: TraceToken, fragment: A)(
        implicit fragA: Fragment[A]): F[Uri] = {

      val content = new ObjectContent[F](
        fragA.content(fragment).covary[F],
        fragA.contentLength(fragment),
        chunked = false,
        fragA.contentType.mediaType,
        fragA.contentType.charset
      )

      val s3Domain = if (config.region == Region.`us-east-1`) {
        "s3.amazonaws.com"
      } else {
        s"s3-${region.value}.amazonaws.com"
      }

      for {
        key <- keys.get(commId, traceToken)
        result <- s3
          .putObject(
            config.bucketName,
            key,
            content,
            Map("comm-id" -> commId, "trace-token" -> traceToken))
          .map { resultOrError =>
            resultOrError.leftWiden[Throwable] *> Uri.fromString(
              s"https://${bucketName.name}.${s3Domain}/${key.value}")
          }
          .rethrow
      } yield result
    }
  }
}
