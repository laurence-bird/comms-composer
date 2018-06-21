package servicetest.util

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectResult
import com.ovoenergy.comms.model.{CommManifest, TemplateManifest}
import com.ovoenergy.comms.templates.extensions.CommManifestExtensions

trait MockTemplates extends CommManifestExtensions {

  def uploadTemplateToS3(commManifest: CommManifest, s3Client: AmazonS3Client, s3BucketName: String): PutObjectResult = {
    uploadTemplateToS3(commManifest.toTemplateManifest, s3Client, s3BucketName)
  }

  def uploadTemplateToS3(templateManifest: TemplateManifest,
                         s3Client: AmazonS3Client,
                         s3BucketName: String): PutObjectResult = {
    // disable chunked encoding to work around https://github.com/jubos/fake-s3/issues/164

    // template
    s3Client.putObject(s3BucketName,
                       s"${templateManifest.id}/${templateManifest.version}/email/subject.txt",
                       "SUBJECT {{profile.firstName}}")
    s3Client.putObject(s3BucketName,
                       s"${templateManifest.id}/${templateManifest.version}/email/body.html",
                       "{{> header}} HTML BODY {{amount}}")
    s3Client.putObject(s3BucketName,
                       s"${templateManifest.id}/${templateManifest.version}/email/body.txt",
                       "{{> header}} TEXT BODY {{amount}}")
    s3Client.putObject(s3BucketName,
                       s"${templateManifest.id}/${templateManifest.version}/sms/body.txt",
                       "{{> header}} SMS BODY {{amount}}")
    s3Client.putObject(s3BucketName,
                       s"${templateManifest.id}/${templateManifest.version}/print/body.html",
                       "Hello {{profile.firstName}}")

    // fragments
    s3Client.putObject(s3BucketName, "fragments/email/html/header.html", "HTML HEADER")
    s3Client.putObject(s3BucketName, "fragments/email/txt/header.txt", "TEXT HEADER")
    s3Client.putObject(s3BucketName, "fragments/sms/txt/header.txt", "SMS HEADER")
  }
}
