package com.ovoenergy.comms.composer

import fs2.{io => _, _}
import io.circe.{Encoder, Decoder}
import java.util.Base64

import scala.util.Try
import io.circe.Decoder._
import org.http4s._
import headers.{`Content-Type` => ContentType}
import org.http4s.MediaType.{`application/pdf` => pdf, `text/html` => html, `text/plain` => text}
import org.http4s.Charset.{`UTF-8` => utf8}
import java.nio.charset.StandardCharsets.{UTF_8 => nioUtf8}

import com.ovoenergy.comms.model.ErrorCode
import com.ovoenergy.comms.templates.model.EmailSender

object model {

  type CommId = String

  type TraceToken = String

  type FailedOr[A] = Either[ComposerError, A]

  case class ComposerError(reason: String, errorCode: ErrorCode)
      extends RuntimeException(s"$errorCode - $reason")

  object Email {
    def chooseSender(template: Templates.Email): EmailSender =
      template.sender.getOrElse(defaultSender)

    val defaultSender = EmailSender("Ovo Energy", "no-reply@ovoenergy.com")

    case class Subject(content: String)
    case class HtmlBody(content: String)
    case class TextBody(content: String)
    case class Sender(content: String)

    case class Rendered(subject: Email.Subject, html: Email.HtmlBody, text: Option[Email.TextBody])
  }

  object Print {
    case class PdfBody(content: Array[Byte])
    case class HtmlBody(htmlBody: String)

    case class RenderedPdf(fragment: PdfBody)

    object RenderedPdf {
      implicit def renderedPrintPdfCirceEncoder: Encoder[RenderedPdf] =
        Encoder.encodeString.contramap[RenderedPdf] { x =>
          // TODO: Sort me out, possible failure
          Base64.getEncoder.encodeToString(x.fragment.content)
        }

      implicit def renderedPrintPdfCirceDecoder: Decoder[Print.RenderedPdf] =
        decodeString
          .emapTry(base64 => Try(Base64.getDecoder.decode(base64)))
          .map(x => Print.RenderedPdf(Print.PdfBody(x)))

    }
  }

  object SMS {
    case class Sender(content: String)
    case class Body(content: String)

    case class Rendered(textBody: SMS.Body)
  }

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
    def strings: Fragment[String] = new Fragment[String] {
      // can't reuse the nio charset in `http4s.Charset`, it's private
      def content(s: String): Stream[Pure, Byte] = Stream.chunk(Chunk.bytes(s.getBytes(nioUtf8)))
      def contentType: ContentType = ContentType(text).withCharset(utf8)
      def contentLength(s: String): Long = s.getBytes(utf8.nioCharset).length
    }

    def htmlStrings: Fragment[String] = new Fragment[String] {
      def content(s: String): Stream[Pure, Byte] = Stream.chunk(Chunk.bytes(s.getBytes(nioUtf8)))
      def contentType: ContentType = ContentType(html).withCharset(utf8)
      def contentLength(s: String): Long = s.getBytes(utf8.nioCharset).length
    }

    def pdfBytes: Fragment[Array[Byte]] = new Fragment[Array[Byte]] {
      def content(b: Array[Byte]): Stream[Pure, Byte] = Stream.chunk(Chunk.bytes(b))
      def contentType: ContentType = ContentType(pdf).withCharset(utf8)
      def contentLength(b: Array[Byte]): Long = b.length
    }

    implicit val emailSubjectFragment: Fragment[Email.Subject] =
      strings.contramap(_.content)

    implicit val emailTextBodyFragment: Fragment[Email.TextBody] =
      strings.contramap(_.content)

    implicit val emailHtmlBodyFragment: Fragment[Email.HtmlBody] =
      htmlStrings.contramap(_.content)

    implicit val printBodyFragment: Fragment[Print.PdfBody] =
      pdfBytes.contramap(_.content)

    implicit val smsSenderFragment: Fragment[SMS.Sender] =
      strings.contramap(_.content)

    implicit val smsBodyFragment: Fragment[SMS.Body] =
      strings.contramap(_.content)
  }
}
