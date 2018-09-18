package com.ovoenergy.comms.composer
package v2

import fs2.{io => _, _}
import io.circe.{Decoder, Encoder}, Decoder._
import java.util.Base64
import scala.util.Try
import io.circe.Decoder._
import org.http4s.MediaType

import com.ovoenergy.comms.templates.model.EmailSender

object model {

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
    case class Body(content: Array[Byte])

    case class RenderedHtml(htmlBody: String)

    case class RenderedPdf(fragment: Body)

    object RenderedPdf {
      implicit def renderedPrintPdfCirceEncoder: Encoder[Print.RenderedPdf] =
        Encoder.encodeString.contramap[RenderedPdf] {
          case pdf =>
            Base64.getEncoder.encodeToString(pdf.fragment.content) // TODO: Sort me out, possible failure
        }

      implicit def renderedPrintPdfCirceDecoder: Decoder[Print.RenderedPdf] =
        decodeString
          .emapTry(base64 => Try(Base64.getDecoder.decode(base64)))
          .map(x => Print.RenderedPdf(Print.Body(x)))

    }
  }

  object SMS {
    case class Rendered(textBody: SMS.Body)
    case class Sender(content: String)
    case class Body(content: String)
  }

  trait Fragment[A] {
    def content(a: A): Stream[Pure, Byte]
    def mediaType(a: A): MediaType
  }
  object Fragment {
    // TODO proper implementations for valid fragments
    implicit def instances[A]: Fragment[A] = new Fragment[A] {
      def content(a: A): Stream[Pure, Byte] = ???
      def mediaType(a: A): MediaType = ???
    }
  }
}
