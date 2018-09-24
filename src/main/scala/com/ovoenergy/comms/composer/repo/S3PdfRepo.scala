package com.ovoenergy.comms.composer.repo

import java.io.ByteArrayInputStream
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.ovoenergy.comms.composer.Logging
import com.ovoenergy.comms.composer.print.RenderedPrintPdf
import com.ovoenergy.comms.model.print.OrchestratedPrintV2

import scala.util.{Failure, Success, Try}

object S3PdfRepo extends Logging {

  case class S3Config(s3Client: AmazonS3Client, bucketName: String)

  def saveRenderedPdf(
      renderedPrintPdf: RenderedPrintPdf,
      incomingEvent: OrchestratedPrintV2,
      s3Config: S3Config): Either[String, String] = {

    val s3Client = s3Config.s3Client
    val bucketName = s3Config.bucketName
    val metadata = new ObjectMetadata()
    val inputStream = new ByteArrayInputStream(renderedPrintPdf.pdfBody)
    val key = buildKey(incomingEvent)

    val result = Try(s3Client.putObject(bucketName, key, inputStream, metadata))

    result match {
      case Success(_) => Right(key)
      case Failure(error) => Left(error.getMessage)
    }

  }

  def buildKey(incomingEvent: OrchestratedPrintV2): String = {
    val templateId = incomingEvent.metadata.templateManifest.id
    val createdAt = incomingEvent.metadata.createdAt
    val tt = incomingEvent.metadata.traceToken
    val itt = incomingEvent.internalMetadata.internalTraceToken

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dateOfCreation =
      LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()).format(dateFormatter)

    s"$templateId/$dateOfCreation/${createdAt.toEpochMilli}-$tt-$itt.pdf"
  }

}
