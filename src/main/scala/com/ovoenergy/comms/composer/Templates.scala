package com.ovoenergy.comms.composer

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ThreadFactory}

import cats.Id
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.implicits._
import com.ovoenergy.comms.composer.model.ComposerError
import com.ovoenergy.comms.model.{TemplateDownloadFailed, TemplateManifest, InvalidTemplate}
import com.ovoenergy.comms.templates.{TemplatesContext, TemplatesRepo}
import com.ovoenergy.comms.templates.model.template.{processed => templates}
import com.ovoenergy.comms.templates.model.template.processed.CommTemplate

import scala.concurrent.ExecutionContext

trait Templates[F[_], Template] {
  def get(manifest: TemplateManifest): F[Template]
}

object Templates {

  type Email = templates.email.EmailTemplate[Id]
  type Print = templates.print.PrintTemplate[Id]
  type Sms = templates.sms.SMSTemplate[Id]

  private val blockingEc: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool(new ThreadFactory {

      private val counter = new AtomicLong(0)

      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r)
        t.setDaemon(true)
        t.setName(s"templates-blocking-${counter.getAndIncrement()}")
        t
      }
    }))

  def sms[F[_]](
      implicit F: Async[F],
      ec: ExecutionContext,
      templatesContext: TemplatesContext): Templates[F, Templates.Sms] =
    new Templates[F, Templates.Sms] {
      def get(manifest: TemplateManifest): F[Sms] = loadTemplate(manifest, _.sms)
    }

  def email[F[_]](
      implicit F: Async[F],
      ec: ExecutionContext,
      templatesContext: TemplatesContext): Templates[F, Templates.Email] =
    new Templates[F, Templates.Email] {
      def get(manifest: TemplateManifest): F[Email] = loadTemplate(manifest, _.email)
    }

  def print[F[_]](
      implicit F: Async[F],
      ec: ExecutionContext,
      templatesContext: TemplatesContext): Templates[F, Templates.Print] =
    new Templates[F, Templates.Print] {
      def get(manifest: TemplateManifest): F[Print] = loadTemplate(manifest, _.print)
    }

  // FIXME It is blocking the main EC
  def loadTemplate[F[_], A](manifest: TemplateManifest, f: CommTemplate[Id] => Option[A])(
      implicit F: Async[F],
      ec: ExecutionContext,
      templatesContext: TemplatesContext): F[A] = {

    Async.shift(blockingEc) >> F
      .delay(TemplatesRepo.getTemplate(templatesContext, manifest))
      .flatMap {
        case Valid(commTemplate) =>
          f(commTemplate).fold(
            F.raiseError[A](ComposerError(s"Template for channel not found", InvalidTemplate)))(
            _.pure[F])
        case Invalid(i) =>
          F.raiseError[A](ComposerError(i.toList.mkString(","), InvalidTemplate))
      }
      .handleErrorWith(e => F.raiseError[A](ComposerError(e.getMessage, TemplateDownloadFailed)))
      .onError {
        case _ => Async.shift(ec)
      }
  }
}
