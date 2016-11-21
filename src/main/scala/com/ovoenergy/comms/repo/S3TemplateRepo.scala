package com.ovoenergy.comms.repo

import cats.Apply
import cats.data.{ReaderT, Validated}
import cats.instances.list._
import com.ovoenergy.comms.email.EmailTemplate
import com.ovoenergy.comms.{Channel, CommManifest, CommType, Mustache}

import scala.util.matching.Regex

object S3TemplateRepo {

  private object Filenames {
    object Email {
      val Subject = "subject.txt"
      val HtmlBody = "body.html"
      val TextBody = "body.txt"
      val Sender = "sender.txt"
    }
  }

  type ErrorOr[A] = Either[String, A]

  // TODO caching: cache template files indefinitely, fragments for 5 minutes
  def getEmailTemplate(commManifest: CommManifest) = ReaderT[ErrorOr, S3Client, EmailTemplate] { s3client =>
    def s3File(filename: String): Option[String] =
      s3client.getUTF8TextFileContent(emailTemplateFileKey(Channel.Email, commManifest, filename))

    val subject =
      Validated.fromOption(s3File(Filenames.Email.Subject).map(Mustache),
                           ifNone = List("Subject file not found on S3"))
    val htmlBody =
      Validated.fromOption(s3File(Filenames.Email.HtmlBody).map(Mustache),
                           ifNone = List("HTML body file not found on S3"))
    val textBody = s3File(Filenames.Email.TextBody).map(Mustache)
    val customSender = s3File(Filenames.Email.Sender)

    val htmlFragments = findHtmlFragments(s3client, commManifest.commType)
    val textFragments = findTextFragments(s3client, commManifest.commType)

    type ValidatedErrorsOr[A] = Validated[List[String], A]
    Apply[ValidatedErrorsOr]
      .map2(subject, htmlBody) {
        case (sub, html) =>
          EmailTemplate(
            sender = customSender,
            subject = sub,
            htmlBody = html,
            textBody = textBody,
            htmlFragments = htmlFragments,
            textFragments = textFragments
          )
      }
      .leftMap(errors => errors.mkString(", "))
      .toEither
  }

  private val HtmlFragmentFile = """.*/fragments/email/html/([^/]+)/fragment.html""".r
  private val TextFragmentFile = """.*/fragments/email/text/([^/]+)/fragment.txt""".r

  private def findHtmlFragments(s3client: S3Client, commType: CommType): Map[String, Mustache] =
    findFragments(s3client, s"${commType.toString.toLowerCase}/fragments/email/html", HtmlFragmentFile)

  private def findTextFragments(s3client: S3Client, commType: CommType): Map[String, Mustache] =
    findFragments(s3client, s"${commType.toString.toLowerCase}/fragments/email/text", TextFragmentFile)

  private def findFragments(s3client: S3Client, prefix: String, regex: Regex): Map[String, Mustache] = {
    s3client
      .listFiles(prefix)
      .collect {
        case key @ `regex`(fragmentName) =>
          s3client.getUTF8TextFileContent(key).map(content => fragmentName -> Mustache(content))
      }
      .flatten
      .toMap

  }

  private def emailTemplateFileKey(channel: Channel, commManifest: CommManifest, filename: String): String =
    s"${commManifest.commType.toString.toLowerCase}/${commManifest.name}/${commManifest.version}/${channel.toString.toLowerCase}/$filename"

}
