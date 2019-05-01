package com.ovoenergy.comms.composer
package rendering

import java.{util => ju}

import cats.effect._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import com.ovoenergy.comms.aws._
import com.ovoenergy.comms.aws.common.CredentialsProvider
import com.ovoenergy.comms.aws.common.model._
import com.ovoenergy.comms.aws.s3.S3
import com.ovoenergy.comms.aws.s3.model._

import com.ovoenergy.comms.composer.model._

class TemplatesSpec extends IntegrationSpec {

  implicit val pc: PatienceConfig = PatienceConfig(scaled(15.seconds), 500.millis)

  val existingBucket = Bucket("ovo-comms-test")
  val nonExistingBucket = Bucket("I-hope-this-one-does-not-exist")
  val existingFragmentId = TemplateFragmentId("/test-templates/subject.txt")

  "Templates" should {
    "return None" when {
      "The template fragment does not exist" in {
        withS3 { s3 =>
          val templates = Templates[IO](s3, existingBucket)
          templates.loadTemplateFragment(TemplateFragmentId(ju.UUID.randomUUID().toString()))
        }.futureValue shouldBe None
      }
    }

    "return the template" when {
      "The template fragment exists" in {
        withS3 { s3 =>
          val templates = Templates[IO](s3, existingBucket)
          templates.loadTemplateFragment(TemplateFragmentId("/test-templates/subject.txt"))
        }.futureValue shouldBe a[Some[_]]
      }
    }

    "fail" when {
      "The bucket does not exist" in {
        withS3 { s3 =>
          val templates = Templates[IO](s3, nonExistingBucket)
          templates.loadTemplateFragment(TemplateFragmentId("/test-templates/subject.txt"))
        }.attempt.futureValue shouldBe a[Left[_, _]]
      }
    }
  }

  def withS3[A](f: S3[IO] => IO[A]): IO[A] = {
    S3.resource[IO](CredentialsProvider.default[IO], Region.`eu-west-1`).use(f)
  }
}
