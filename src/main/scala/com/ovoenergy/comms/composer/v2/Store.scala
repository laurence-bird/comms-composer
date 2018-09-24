package com.ovoenergy.comms.composer
package v2

import java.util.UUID

import cats.Applicative
import cats.implicits._
import cats.effect.{Sync, Effect}
import com.ovoenergy.comms.aws._
import common.CredentialsProvider
import common.model._
import s3.S3
import s3.model._
import org.http4s.Uri
import fs2._
import model.Fragment
import org.http4s.client.blaze._

trait Store[F[_]] {
  def upload[A: Fragment](commId: CommId, traceToken: TraceToken, fragment: A): F[Uri]
}

object Store {

  case class Config(bucketName: Bucket, region: Region = Region.`eu-west-1`)

  trait Keys[F[_]] {
    def get(commId: CommId, traceToken: TraceToken): F[Key]
  }

  class RandomSuffixKeys[F[_]: Sync] extends Keys[F] {
    override def get(commId: CommId, traceToken: TraceToken): F[Key] = {
      Sync[F].delay(Key(s"${commId}-${UUID.randomUUID()}"))
    }
  }

  def stream[F[_]: Effect](config: Config): Stream[F, Store[F]] = {
    Http1Client.stream[F]().map { client =>
      apply(
        new S3[F](client, CredentialsProvider.default[F], config.region),
        config,
        new RandomSuffixKeys[F])
    }
  }

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
