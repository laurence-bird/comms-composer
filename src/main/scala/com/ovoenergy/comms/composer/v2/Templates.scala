package com.ovoenergy.comms.composer
package v2

import cats.Id
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Effect
import com.ovoenergy.comms.model.{TemplateDownloadFailed, TemplateManifest}
import com.ovoenergy.comms.templates.{TemplatesContext, TemplatesRepo}
import com.ovoenergy.comms.templates.model.template.{processed => templates}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate
import com.ovoenergy.comms.templates.s3.S3Prefix

trait Templates[F[_], Template] {
  def get(manifest: TemplateManifest): F[Template]
}

object Templates {
  type Email = templates.email.EmailTemplate[Id]
  type Print = templates.print.PrintTemplate[Id]
  type Sms = templates.sms.SMSTemplate[Id]

  def sms[F[_]](
                 implicit F: Effect[F],
                 templatesContext: TemplatesContext): Templates[F, Templates.Sms] =
    new Templates[F, Templates.Sms] {
      def get(manifest: TemplateManifest): F[Sms] = loadTemplate(manifest, _.sms)
    }

  def email[F[_]](
                   implicit F: Effect[F],
                   templatesContext: TemplatesContext): Templates[F, Templates.Email] =
    new Templates[F, Templates.Email] {
      def get(manifest: TemplateManifest): F[Email] = loadTemplate(manifest, _.email)
    }

  def print[F[_]](
                   implicit F: Effect[F],
                   templatesContext: TemplatesContext): Templates[F, Templates.Print] =
    new Templates[F, Templates.Print] {
      def get(manifest: TemplateManifest): F[Print] = loadTemplate(manifest, _.print)
    }

  def loadTemplate[F[_], A](manifest: TemplateManifest, f: CommTemplate[Id] => Option[A])(
    implicit F: Effect[F],
    templatesContext: TemplatesContext): F[A] = {
    TemplatesRepo.getTemplate(templatesContext, manifest) match {
      case Valid(commTemplate) =>
        f(commTemplate) match {
          case Some(t) => F.pure(t)
          case None =>
            F.raiseError(ComposerError("Template for channel not found", TemplateDownloadFailed))
        }
      case Invalid(errs) =>
        F.raiseError(ComposerError(s"""The specified template (${S3Prefix
          .fromTemplateManifest(manifest)}) does not exist or is not valid.
                                      |The following errors were encountered:
                                      |
                 |${errs.toList.map(e => s" - $e").mkString("\n")}
                                      |""".stripMargin, TemplateDownloadFailed))
    }
  }
}