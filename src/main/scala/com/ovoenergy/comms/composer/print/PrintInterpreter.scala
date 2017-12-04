package com.ovoenergy.comms.composer.print

import java.time.Clock

import cats.syntax.either._
import cats.~>
import com.ovoenergy.comms.composer.rendering.templating.{PrintTemplateData, PrintTemplateRendering}
import com.ovoenergy.comms.composer.repo.{S3PdfRepo, S3TemplateRepo}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.templates.TemplatesContext
import com.ovoenergy.comms.composer.Interpreters._
import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import com.ovoenergy.comms.composer.rendering.pdf.{DocRaptorClient, DocRaptorConfig, DocRaptorError}
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
          case RetrieveTemplate(event) =>
            try {
              S3TemplateRepo
                .getPrintTemplate(event.metadata.commManifest)
                .run(printContext.templateContext)
                .leftMap(err => failPrint(err, event, TemplateDownloadFailed))
            } catch {
              case NonFatal(e) => Left(failPrintWithException(e, event))
            }
          case RenderPrintHtml(event, template) =>
            try {
              PrintTemplateRendering
                .renderHtml(PrintTemplateData(event.templateData, event.customerProfile, event.address),
                            event.metadata.commManifest,
                            template,
                            Clock.systemDefaultZone())
                .leftMap(templateErrors => failPrint(templateErrors.reason, event, templateErrors.errorCode))
            } catch {
              case NonFatal(e) => Left(failPrintWithException(e, event))
            }
          case RenderPrintPdf(event, renderedPrintHtml) =>
            DocRaptorClient
              .renderPdf(printContext, renderedPrintHtml)
              .leftMap((error: DocRaptorError) => {
                warn(event)(s"Call to docraptor failed with error message: \n ${error.errorDetails}")
                failPrint(s"Failed to render pdf: ${error.httpError}", event, CompositionError)
              })
          case PersistRenderedPdf(event, renderedPrintPdf) =>
            S3PdfRepo
              .saveRenderedPdf(renderedPrintPdf, event, printContext.s3Config)
              .leftMap(error => failPrint(s"Failed to persist rendered pdf: $error", event, CompositionError))
        }
      }
    }
  }

  private def failPrint(reason: String, event: OrchestratedPrint, errorCode: ErrorCode): FailedV2 = {
    warn(event)(s"Failed to compose print. Reason: $reason")
    buildFailedEvent(reason, event.metadata, event.internalMetadata, errorCode)
  }

  private def failPrintWithException(exception: Throwable, event: OrchestratedPrint): FailedV2 = {
    warnT(event)(s"Failed to compose print because an unexpected exception occurred", exception)
    buildFailedEvent(s"Exception occurred ($exception)", event.metadata, event.internalMetadata, CompositionError)
  }

}
