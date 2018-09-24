package com.ovoenergy.comms.composer.repo

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import com.ovoenergy.comms.composer.TestGenerators
import com.ovoenergy.comms.model.Arbitraries
import com.ovoenergy.comms.model.print.{OrchestratedPrint, OrchestratedPrintV2}
import org.scalatest.{FlatSpec, Matchers}

class S3PdfRepoSpec extends FlatSpec with Matchers with Arbitraries with TestGenerators {

  it should "build the correct key for the pdf file" in {
    val incomingEvent = generate[OrchestratedPrintV2]
    val templateId = incomingEvent.metadata.templateManifest.id
    val createdAt = incomingEvent.metadata.createdAt
    val tt = incomingEvent.metadata.traceToken
    val itt = incomingEvent.internalMetadata.internalTraceToken

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dateOfCreation =
      LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()).format(dateFormatter)

    val expected = s"$templateId/$dateOfCreation/${createdAt.toEpochMilli}-$tt-$itt.pdf"

    val result = S3PdfRepo.buildKey(incomingEvent)

    result shouldBe expected
  }
}
