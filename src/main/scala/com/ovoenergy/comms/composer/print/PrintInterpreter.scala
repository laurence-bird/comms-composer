package com.ovoenergy.comms.composer.print

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.Interpreters
import com.ovoenergy.comms.composer.rendering.templating.{PrintTemplateData, PrintTemplateRendering}
import com.ovoenergy.comms.composer.repo.{S3PdfRepo, S3TemplateRepo}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.templates.TemplatesContext
import com.ovoenergy.comms.composer.Interpreters._
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.rendering.pdf.{DocRaptorClient, DocRaptorConfig, DocRaptorError}
import com.ovoenergy.comms.composer.rendering.templating
import com.ovoenergy.comms.composer.repo.S3PdfRepo.S3Config
import com.ovoenergy.comms.model
import okhttp3.{Request, Response}

import scala.util.Try
import scala.util.control.NonFatal

object PrintInterpreter {
  case class PrintContext(docRaptorConfig: DocRaptorConfig,
                          s3Config: S3Config,
                          retryConfig: RetryConfig,
                          templateContext: TemplatesContext,
                          httpClient: Request => Try[Response])

  def apply(printContext: PrintContext): PrintComposerA ~> FailedOr = {
    new (PrintComposerA ~> FailedOr) {
      override def apply[A](op: PrintComposerA[A]): FailedOr[A] = {
        op match {
          case RetrieveTemplate(commManifest) =>
            try {
              S3TemplateRepo
                .getPrintTemplate(commManifest)
                .run(printContext.templateContext)
                .leftMap(err => failPrint(err, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failPrintWithException(e))
            }
          case RenderPrintHtml(handlebarsData: templating.CommTemplateData, template, commManifest) =>
            try {
              PrintTemplateRendering
                .renderHtml(handlebarsData.buildHandlebarsData, commManifest, template, Clock.systemDefaultZone())
                .leftMap(templateErrors => failPrint(templateErrors.reason, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failPrintWithException(e))
            }
          case RenderPrintPdf(renderedPrintHtml) =>
            DocRaptorClient
              .renderPdf(printContext, renderedPrintHtml)
              .leftMap((error: DocRaptorError) => {
                failPrint(s"Failed to render pdf: ${error.httpError}", CompositionError)
              })
          case PersistRenderedPdf(event, renderedPrintPdf) =>
            S3PdfRepo
              .saveRenderedPdf(renderedPrintPdf, event, printContext.s3Config)
              .leftMap(error => failPrint(s"Failed to persist rendered pdf: $error", CompositionError))
        }
      }
    }
  }

  private def failPrint(reason: String, errorCode: ErrorCode): Interpreters.Error = {
    Interpreters.Error(reason, errorCode)
  }

  private def failPrintWithException(exception: Throwable): Interpreters.Error = {
    Interpreters.Error(s"Exception occurred ($exception)", CompositionError)
  }

}
