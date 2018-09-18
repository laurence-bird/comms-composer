package com.ovoenergy.comms.composer.print

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.email.HashString
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.rendering.pdf.{DocRaptorClient, DocRaptorConfig, DocRaptorError}
import com.ovoenergy.comms.composer.rendering.templating
import com.ovoenergy.comms.composer.rendering.templating.PrintTemplateRendering
import com.ovoenergy.comms.composer.repo.S3PdfRepo.S3Config
import com.ovoenergy.comms.composer.repo.{S3PdfRepo, S3TemplateRepo}
import com.ovoenergy.comms.composer.{ComposerError, FailedOr, Logging}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.TemplatesContext
import com.ovoenergy.comms.templates.util.Hash
import okhttp3.{Request, Response}

import scala.util.Try
import scala.util.control.NonFatal

object PrintInterpreter extends Logging {
  case class PrintContext(docRaptorConfig: DocRaptorConfig,
                          s3Config: S3Config,
                          retryConfig: RetryConfig,
                          templateContext: TemplatesContext,
                          httpClient: Request => Try[Response])

  def apply(printContext: PrintContext): PrintComposerA ~> FailedOr = {
    new (PrintComposerA ~> FailedOr) {
      override def apply[A](op: PrintComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(templateManifest) =>
            try {
              S3TemplateRepo
                .getPrintTemplate(templateManifest)
                .run(printContext.templateContext)
                .leftMap { err =>
                  warn(templateManifest)("Failed to retrieve template")
                  failPrint(err, TemplateDownloadFailed)
                }
            } catch {
              case NonFatal(e) => {
                warnWithException(templateManifest)("Failed to retrieve template")(e)
                Left(failPrintWithException(e))
              }
            }
          case RenderPrintHtml(handlebarsData: templating.CommTemplateData, template, commManifest) =>
            try {
              PrintTemplateRendering
                .renderHtml(handlebarsData.buildHandlebarsData, commManifest, template, Clock.systemDefaultZone())
                .leftMap { templateErrors =>
                  warn(commManifest)(s"Failed to render print HTML ${templateErrors.reason}")
                  failPrint(templateErrors.reason, templateErrors.errorCode)
                }
            } catch {
              case NonFatal(e) => {
                warnWithException(commManifest)("Failed to render print HTML")(e)
                Left(failPrintWithException(e))
              }
            }
          case RenderPrintPdf(renderedPrintHtml: RenderedPrintHtml, commManifest) =>
            val result = DocRaptorClient
              .renderPdf(printContext, renderedPrintHtml)
              .leftMap((error: DocRaptorError) => {
                failPrint(s"Failed to render pdf: ${error.httpError}", CompositionError)
              })
            result.fold(e => warn(commManifest)(e.reason), _ => info(commManifest)("Persisted PDF successfully"))
            result
          case PersistRenderedPdf(event, renderedPrintPdf) =>
            val result = S3PdfRepo
              .saveRenderedPdf(renderedPrintPdf, event, printContext.s3Config)
              .leftMap(error => failPrint(s"Failed to persist rendered pdf: $error", CompositionError))

            result.fold(e => warn(event)(e.reason), _ => info(event)("Persisted PDF successfully"))

            result
          case HashString(str) => Right(Hash(str))
        }
      }
    }
  }

  private def failPrint(reason: String, errorCode: ErrorCode): ComposerError = {
    ComposerError(reason, errorCode)
  }

  private def failPrintWithException(exception: Throwable): ComposerError = {
    ComposerError(s"Exception occurred ($exception)", CompositionError)
  }

}
