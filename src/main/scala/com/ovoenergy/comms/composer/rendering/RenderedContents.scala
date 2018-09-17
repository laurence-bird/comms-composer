package com.ovoenergy.comms.composer
package rendering

import java.util.UUID

import cats.effect.Effect
import fs2._
import org.aws4s.s3.{BucketName, ObjectPath, S3}
import org.http4s.Uri
import org.http4s.client.Client
import cats.implicits._

trait RenderedContents[F[_]] {
  def store(commId: CommId, traceToken: TraceToken, content: RenderedContent[F]): F[Uri]
}

sealed trait RenderedContent[F[_]] {
  def content: Stream[F, Byte]
}

object RenderedContent {

  case class EmailSubject[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]
  case class EmailHtmBody[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]
  case class EmailTextBody[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]
  case class EmailSender[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]

  case class SmsSender[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]
  case class SmsBody[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]

  case class PrintBody[F[_]](content: Stream[F, Byte]) extends RenderedContent[F]

  def fromAws4S[F[_]: Effect](s3: S3[F], bucket: BucketName): RenderedContents[F] = new RenderedContents[F] {

    override def store(commId: CommId, traceToken: TraceToken, content: RenderedContent[F]): F[Uri] = {

      val contentType = content match {
        case EmailSender(_)   => "email-sender"
        case EmailSubject(_)  => "email-subject"
        case EmailHtmBody(_)  => "email-html-body"
        case EmailTextBody(_) => "email-text-body"

        case SmsSender(_) => "sms-sender"
        case SmsBody(_)   => "sms-body"

        case PrintBody(_) => "print-body"
      }

      val key = s"$commId/$contentType"

      s3.putObject(
          bucket,
          ObjectPath(key),
          content.content.pure[F]
        )
        // TODO Use the total version
        .as(Uri.unsafeFromString(s"s3://${bucket.value}/$key"))
    }
  }
}
