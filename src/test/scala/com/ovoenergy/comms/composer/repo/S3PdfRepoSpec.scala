package com.ovoenergy.comms.composer.repo

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

import com.ovoenergy.comms.composer.TestGenerators
import com.ovoenergy.comms.model.print.OrchestratedPrint
import org.scalatest.{FlatSpec, Matchers}
import org.scalacheck.Shapeless._

class S3PdfRepoSpec extends FlatSpec with Matchers with TestGenerators {

  it should "build the correct key for the pdf file" in {
    val incomingEvent = generate[OrchestratedPrint]
    val commName = incomingEvent.metadata.commManifest.name
    val createdAt = incomingEvent.metadata.createdAt
    val tt = incomingEvent.metadata.traceToken
    val itt = incomingEvent.internalMetadata.internalTraceToken

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dateOfCreation = LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()).format(dateFormatter)

    val expected = s"$commName/$dateOfCreation/${createdAt.toEpochMilli}-$tt-$itt.pdf"

    val result = S3PdfRepo.buildKey(incomingEvent)

    result shouldBe expected
  }
}
