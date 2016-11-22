package com.ovoenergy.comms.repo

import cats.Apply
import cats.data.{NonEmptyList, ReaderT, Validated, ValidatedNel}
import cats.instances.list._
import cats.syntax.traverse._
import cats.instances.option._
import com.ovoenergy.comms.email.{EmailSender, EmailTemplate}
import com.ovoenergy.comms._

import scala.util.matching.Regex

object S3TemplateRepo extends Logging {

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

    type ValidatedErrorsOr[A] = ValidatedNel[String, A]

    val subject: ValidatedErrorsOr[Mustache] =
      Validated.fromOption(s3File(Filenames.Email.Subject).map(Mustache),
                           ifNone = NonEmptyList.of("Subject file not found on S3"))
    val htmlBody: ValidatedErrorsOr[Mustache] =
      Validated.fromOption(s3File(Filenames.Email.HtmlBody).map(Mustache),
                           ifNone = NonEmptyList.of("HTML body file not found on S3"))
    val textBody: Option[Mustache] = s3File(Filenames.Email.TextBody).map(Mustache)
    val customSender: ValidatedErrorsOr[Option[EmailSender]] =
      s3File(Filenames.Email.Sender).map(EmailSender.parse).sequenceU

    val htmlFragments = findHtmlFragments(s3client, commManifest.commType)
    val textFragments = findTextFragments(s3client, commManifest.commType)

    Apply[ValidatedErrorsOr]
      .map3(subject, htmlBody, customSender) {
        case (sub, html, sender) =>
          EmailTemplate(
            subject = sub,
            htmlBody = html,
            textBody = textBody,
            sender = sender,
            htmlFragments = htmlFragments,
            textFragments = textFragments
          )
      }
      .leftMap(errors => errors.toList.mkString(", "))
      .toEither
  }

  private val HtmlFragmentFile = """.*/fragments/email/html/([a-zA-Z0-9-_]+).html""".r
  private val TextFragmentFile = """.*/fragments/email/text/([a-zA-Z0-9-_]+).txt""".r

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
