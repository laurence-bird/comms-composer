package servicetest.util

import cats.implicits._
import cats.effect.Sync

import com.ovoenergy.comms.aws.s3.S3
import com.ovoenergy.comms.aws.s3.model.{Key, Bucket, ObjectContent}
import com.ovoenergy.comms.model.{TemplateManifest}
import com.ovoenergy.comms.templates.extensions.CommManifestExtensions

trait MockTemplates extends CommManifestExtensions {


}
