package com.ovoenergy.comms.repo

import com.ovoenergy.comms.email.{EmailSender, EmailTemplate}
import com.ovoenergy.comms.{CommManifest, CommType, Mustache}
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class S3TemplateRepoSpec extends FlatSpec with Matchers with EitherValues {

  def s3(objects: Map[String, String], childLists: Map[String, Seq[String]] = Map.empty) = new S3Client {
    override def listFiles(prefix: String): Seq[String] = childLists.getOrElse(prefix, Nil)
    override def getUTF8TextFileContent(key: String): Option[String] = objects.get(key)
  }

  val commManifest = CommManifest(CommType.Service, "payment", "0.1")

  it should "download a simple template" in {
    val s3client = s3(
      Map(
        "service/payment/0.1/email/subject.txt" -> "the subject",
        "service/payment/0.1/email/body.html" -> "the HTML body"
      ))
    val template = S3TemplateRepo.getEmailTemplate(commManifest).run(s3client)
    template should be(
      Right(
        EmailTemplate(
          subject = Mustache("the subject"),
          htmlBody = Mustache("the HTML body"),
          textBody = None,
          sender = None,
          htmlFragments = Map.empty,
          textFragments = Map.empty
        )))
  }

  it should "download a template with a text body and a custom sender" in {
    val s3client = s3(
      Map(
        "service/payment/0.1/email/subject.txt" -> "the subject",
        "service/payment/0.1/email/body.html" -> "the HTML body",
        "service/payment/0.1/email/body.txt" -> "the text body",
        "service/payment/0.1/email/sender.txt" -> "custom sender <foo@ovoenergy.com>"
      ))
    val template = S3TemplateRepo.getEmailTemplate(commManifest).run(s3client)
    template should be(
      Right(
        EmailTemplate(
          subject = Mustache("the subject"),
          htmlBody = Mustache("the HTML body"),
          textBody = Some(Mustache("the text body")),
          sender = Some(EmailSender("custom sender", "foo@ovoenergy.com")),
          htmlFragments = Map.empty,
          textFragments = Map.empty
        )))
  }

  it should "validate the custom sender if it is present" in {
    val s3client = s3(
      Map(
        "service/payment/0.1/email/subject.txt" -> "the subject",
        "service/payment/0.1/email/body.html" -> "the HTML body",
        "service/payment/0.1/email/sender.txt" -> "#yolo"
      ))
    val errorMsg = S3TemplateRepo.getEmailTemplate(commManifest).run(s3client).left.value
    errorMsg should include("#yolo")
  }

  it should "fail if subject or HTML body are missing" in {
    val s3client = s3(Map.empty)
    val errorMsg = S3TemplateRepo.getEmailTemplate(commManifest).run(s3client).left.value
    errorMsg should include("Subject file not found")
    errorMsg should include("HTML body file not found")
  }

  it should "download a template with fragments" in {
    val s3client = s3(
      objects = Map(
        "service/payment/0.1/email/subject.txt" -> "the subject",
        "service/payment/0.1/email/body.html" -> "the HTML body",
        "service/fragments/email/html/header.html" -> "the HTML header",
        "service/fragments/email/html/thing.html" -> "another HTML fragment",
        "service/fragments/email/text/header.txt" -> "the text header"
      ),
      childLists = Map(
        "service/fragments/email/html" -> Seq(
          "service/fragments/email/html/header.html",
          "service/fragments/email/html/thing.html"
        ),
        "service/fragments/email/text" -> Seq(
          "service/fragments/email/text/header.txt"
        )
      )
    )
    val template = S3TemplateRepo.getEmailTemplate(commManifest).run(s3client)
    template should be(
      Right(
        EmailTemplate(
          subject = Mustache("the subject"),
          htmlBody = Mustache("the HTML body"),
          textBody = None,
          sender = None,
          htmlFragments = Map(
            "header" -> Mustache("the HTML header"),
            "thing" -> Mustache("another HTML fragment")
          ),
          textFragments = Map(
            "header" -> Mustache("the text header")
          )
        )))
  }

}
