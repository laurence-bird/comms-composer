package com.ovoenergy.comms.composer

import fs2.{io => _, _}
import io.circe.{Encoder, Decoder}
import java.util.Base64

import cats.Show

import scala.util.Try
import io.circe.Decoder._
import org.http4s._
import headers.{`Content-Type` => ContentType}
import org.http4s.MediaType.application.pdf
import org.http4s.MediaType.text.{plain, html}
import org.http4s.Charset.{`UTF-8` => utf8}
import java.nio.charset.StandardCharsets.{UTF_8 => nioUtf8}

import com.ovoenergy.comms.model.{ErrorCode, TemplateManifest}
import com.ovoenergy.comms.templates.model.EmailSender

object model {

  type CommId = String

  type TraceToken = String

  type FailedOr[A] = Either[ComposerError, A]

  case class TemplateFragmentId(path: String) extends AnyVal

  sealed trait TemplateFragmentType
  object TemplateFragmentType {
    object Email {
      case object Sender extends TemplateFragmentType
      case object Subject extends TemplateFragmentType
      case object HtmlBody extends TemplateFragmentType
      case object TextBody extends TemplateFragmentType
    }

    object Sms {
      case object Body extends TemplateFragmentType
    }
    object Print {
      case object Body extends TemplateFragmentType
    }
  }

  case class TemplateFragment(value: String) extends AnyVal

  case class RenderedFragment(value: String) extends AnyVal

  // TODO Array[Byte] is mutable
  case class RenderedPdfFragment(value: Array[Byte]) extends AnyVal

  case class EmailTemplate(
      subject: TemplateFragment,
      htmlBody: TemplateFragment,
      textBody: Option[TemplateFragment],
      sender: Option[TemplateFragment]
  )

  object RenderedEmail {
    case class Subject(uri: Uri) extends AnyVal
    case class HtmlBody(uri: Uri) extends AnyVal
    case class TextBody(uri: Uri) extends AnyVal
  }

  case class RenderedEmail(
      sender: EmailSender,
      subject: RenderedEmail.Subject,
      htmlBody: RenderedEmail.HtmlBody,
      textBody: Option[RenderedEmail.TextBody]
  )

  object RenderedSms {
    case class Sender(content: String)
    case class Body(uri: Uri)
  }
  case class RenderedSms(
      sender: RenderedSms.Sender,
      body: RenderedSms.Body,
  )

  object RenderedPrint {
    // TODO Array is mutable
    case class Body(uri: Uri)
  }

  // TODO What's about sender ???
  case class RenderedPrint(
      body: RenderedPrint.Body
  )

  case class PrintTemplate(
      body: TemplateFragment
  )

  case class SmsTemplate(
      body: TemplateFragment
  )

  case class ComposerError(reason: String, errorCode: ErrorCode)
      extends RuntimeException(s"$errorCode - $reason")

  trait Fragment[A] { self =>
    def content(a: A): Stream[Pure, Byte]
    def contentType: ContentType
    def contentLength(a: A): Long

    def contramap[B](f: B => A): Fragment[B] = new Fragment[B] {
      def content(b: B): Stream[Pure, Byte] = self.content(f(b))
      def contentLength(b: B): Long = self.contentLength(f(b))
      def contentType: ContentType = self.contentType
    }
  }

  object Fragment {

    def apply[A](implicit fa: Fragment[A]): Fragment[A] = fa

    def strings: Fragment[String] = new Fragment[String] {
      // can't reuse the nio charset in `http4s.Charset`, it's private
      def content(s: String): Stream[Pure, Byte] = Stream.chunk(Chunk.bytes(s.getBytes(nioUtf8)))
      def contentType: ContentType = ContentType(MediaType.text.plain).withCharset(utf8)
      def contentLength(s: String): Long = s.getBytes(utf8.nioCharset).length
    }

    def pdfBytes: Fragment[Array[Byte]] = new Fragment[Array[Byte]] {
      def content(b: Array[Byte]): Stream[Pure, Byte] = Stream.chunk(Chunk.bytes(b))
      def contentType: ContentType = ContentType(pdf).withCharset(utf8)
      def contentLength(b: Array[Byte]): Long = b.length
    }

    implicit val textFragment: Fragment[RenderedFragment] =
      strings.contramap(_.value)

    implicit val pdfFragment: Fragment[RenderedPdfFragment] =
      pdfBytes.contramap(_.value)

  }

  implicit val templateManifestShow: Show[TemplateManifest] =
    (t: TemplateManifest) => s"${t.id}:${t.version}"

}
