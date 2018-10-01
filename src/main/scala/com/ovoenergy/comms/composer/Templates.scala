package com.ovoenergy.comms.composer

import cats.Id
import cats.data.Validated.{Invalid, Valid}
import cats.effect.{Effect, Sync}
import com.ovoenergy.comms.model.TemplateManifest
import com.ovoenergy.comms.templates.{TemplatesContext, TemplatesRepo}
import com.ovoenergy.comms.templates.model.template.{processed => templates}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate

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

  // FIXME It is blocking the main EC
  def loadTemplate[F[_], A](manifest: TemplateManifest, f: CommTemplate[Id] => Option[A])(
      implicit F: Sync[F],
      templatesContext: TemplatesContext): F[A] = {
    TemplatesRepo.getTemplate(templatesContext, manifest) match {
      case Valid(commTemplate) =>
        f(commTemplate) match {
          case Some(t) => F.pure(t)
          case None =>
            F.raiseError(new RuntimeException(s"Template for channel not found")) // TODO: change to ComposerError
        }
      case Invalid(i) =>
        F.raiseError(new RuntimeException(i.toList.mkString(","))) // TODO: change to ComposerError
    }
  }
}
