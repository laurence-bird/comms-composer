package com.ovoenergy.comms.composer
package rendering

import cats._
import cats.data.EitherT
import cats.effect.Async
import cats.implicits._

import fs2._
import fs2.text._

import com.ovoenergy.comms.model.{TemplateDownloadFailed, TemplateManifest, InvalidTemplate}
import com.ovoenergy.comms.templates.{TemplatesContext, TemplatesRepo}
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.{processed => templates}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.aws.s3.S3
import com.ovoenergy.comms.aws.s3.model.{Bucket, Key, Error => S3Error}

import com.ovoenergy.comms.composer.model._

trait Templates[F[_]] {

  def loadTemplateFragment(id: TemplateFragmentId): F[Option[TemplateFragment]]
}

object Templates {

  def apply[F[_]: Async](s3: S3[F], bucket: Bucket): Templates[F] = new Templates[F] {
    def loadTemplateFragment(
        id: TemplateFragmentId
    ): F[Option[TemplateFragment]] = {
      EitherT(s3.getObjectAs(bucket = bucket, key = Key(id.value))((_, data) =>
        data.through(utf8Decode).compile.string.map(TemplateFragment.apply)))
        .map(_.some)
        .leftFlatMap { error =>
          error.code match {
            case S3Error.Code("NoSuchKey") => EitherT.pure[F, S3Error](none[TemplateFragment])
            case _ => EitherT.leftT[F, Option[TemplateFragment]](error)
          }
        }
        .leftWiden[Throwable]
        .rethrowT
    }
  }
}
