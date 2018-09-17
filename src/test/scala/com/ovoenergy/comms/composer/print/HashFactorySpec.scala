package com.ovoenergy.comms.composer.print

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import com.ovoenergy.comms.composer.rendering.{HashFactory, PrintHashData}
import com.ovoenergy.comms.model.print.OrchestratedPrintV2
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.util.Hash
import org.scalatest.{FlatSpec, Matchers}

class HashFactorySpec extends FlatSpec with Matchers {

  val incomingEvent = OrchestratedPrintV2(
    metadata = MetadataV3(
      createdAt = Instant.now,
      eventId = UUID.randomUUID().toString,
      traceToken = "abc",
      deliverTo = Customer("customerId"),
      templateManifest = TemplateManifest(Hash("test-template"), "0.1"),
      commId = "1234",
      friendlyDescription = "test message",
      source = "test",
      canary = true,
      sourceMetadata = None,
      triggerSource = "Laurence"
    ),
    address = CustomerAddress("line1", Some("line2"), "London", Some("Middlesex"), "HA9 8PH", Some("UK")),
    customerProfile = Some(CustomerProfile("Joe", "Bloggs")),
    templateData = Map.empty,
    internalMetadata = InternalMetadata("HI"),
    expireAt = None
  )

  val hashData = PrintHashData(
    incomingEvent.customerProfile,
    incomingEvent.address,
    incomingEvent.templateData,
    incomingEvent.metadata.templateManifest)

  it should "create the correct hashedComm" in {

    val expected = MessageDigest
      .getInstance("MD5")
      .digest(hashData.toString.getBytes)

    HashFactory.getHashedComm(hashData) should be(new String(expected))
  }

  it should "create a different hashedComm" in {

    val badHashData = PrintHashData(
      incomingEvent.customerProfile,
      CustomerAddress("line1", Some("line2"), "London", Some("Middlesex"), "HA98PH", Some("UK")),
      incomingEvent.templateData,
      incomingEvent.metadata.templateManifest
    )

    val expected = MessageDigest
      .getInstance("MD5")
      .digest(hashData.toString.getBytes)

    HashFactory.getHashedComm(badHashData) shouldNot be(new String(expected))
  }
}
