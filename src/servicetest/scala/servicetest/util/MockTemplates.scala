package servicetest.util

import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.comms.model.CommManifest
import com.ovoenergy.comms.templates.extensions.CommManifestExtensions

trait MockTemplates extends CommManifestExtensions {

  def uploadTemplateToS3(commManifest: CommManifest, s3Client: AmazonS3Client, s3BucketName: String) = {
    // disable chunked encoding to work around https://github.com/jubos/fake-s3/issues/164

    val templateManifest = commManifest.toTemplateManifest
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
