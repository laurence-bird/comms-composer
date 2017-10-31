package com.ovoenergy.comms.composer.print

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

import cats.{Id, ~>}
import com.ovoenergy.comms.composer.rendering.{HashFactory, PrintHashData}
import com.ovoenergy.comms.model
import com.ovoenergy.comms.model.print.OrchestratedPrint
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.{HandlebarsTemplate, RequiredTemplateData}
import com.ovoenergy.comms.templates.model.template.processed.print.PrintTemplate
import org.scalatest.{FlatSpec, Matchers}

class HashFactorySpec extends FlatSpec with Matchers {

  val incomingEvent = OrchestratedPrint(
    metadata = MetadataV2(
      createdAt = Instant.now,
      eventId = UUID.randomUUID().toString,
      traceToken = "abc",
      Customer("customerId"),
      commManifest = CommManifest(model.Service, "test-template", "0.1"),
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None,
      triggerSource = "Laurence"
    ),
    address = CustomerAddress("line1", "line2", "London", "Middlesex", "HA9 8PH", "UK"),
    customerProfile = Some(CustomerProfile("Joe", "Bloggs")),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  val hashData = PrintHashData(incomingEvent.customerProfile,
                               incomingEvent.address,
                               incomingEvent.templateData,
                               incomingEvent.metadata.commManifest)

  it should "create the correct hashedComm" in {

    val expected = MessageDigest
      .getInstance("MD5")
      .digest(hashData.toString.getBytes)

    HashFactory.getHashedComm(hashData) should be(new String(expected))
  }

  it should "create a different hashedComm" in {

    val badHashData = PrintHashData(
      incomingEvent.customerProfile,
      CustomerAddress("line1", "line2", "London", "Middlesex", "HA98PH", "UK"),
      incomingEvent.templateData,
      incomingEvent.metadata.commManifest
    )

    val expected = MessageDigest
      .getInstance("MD5")
      .digest(hashData.toString.getBytes)

    HashFactory.getHashedComm(badHashData) shouldNot be(new String(expected))
  }
}
