package com.ovoenergy.comms.composer
package rendering

import fs2._
import org.http4s.Uri

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
}