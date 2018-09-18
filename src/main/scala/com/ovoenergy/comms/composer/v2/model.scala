package com.ovoenergy.comms.composer
package v2

import com.ovoenergy.comms.templates.model.EmailSender

object model {

  sealed trait Fragment
  object Fragment {
    case class EmailSubject(content: String) extends Fragment
    case class EmailHtmBody(content: String) extends Fragment
    case class EmailTextBody(content: String) extends Fragment
    case class EmailSender(content: String) extends Fragment
    case class SmsSender(content: String) extends Fragment
    case class SmsBody(content: String) extends Fragment
    case class PrintBody(content: Array[Byte]) extends Fragment
  }

  object Email {
    def chooseSender(template: Templates.Email): EmailSender =
      template.sender.getOrElse(defaultSender)

    val defaultSender = EmailSender("Ovo Energy", "no-reply@ovoenergy.com")

    case class Rendered(subject: Fragment, htmlBody: Fragment, textBody: Option[Fragment])
  }

  object Print {
    import io.circe.{Decoder, Encoder}
    import java.util.Base64
    import io.circe.Decoder._

    import scala.util.Try

    case class RenderedHtml(htmlBody: String)

    case class RenderedPdf(fragment: Fragment)

    object RenderedPdf {
      implicit def renderedPrintPdfCirceEncoder: Encoder[Print.RenderedPdf] =
        Encoder.encodeString.contramap[RenderedPdf] {
          case Print.RenderedPdf(Fragment.PrintBody(body)) =>
            Base64.getEncoder.encodeToString(body) // TODO: Sort me out, partial match
        }

      implicit def renderedPrintPdfCirceDecoder: Decoder[Print.RenderedPdf] =
        decodeString
          .emapTry(base64 => Try(Base64.getDecoder.decode(base64)))
          .map(x => Print.RenderedPdf(Fragment.PrintBody(x)))

    }
  }

  object SMS {
    case class Rendered(textBody: Fragment)
  }

}
