package com.ovoenergy.comms.composer

import java.time.{Duration, Instant, OffsetDateTime, ZonedDateTime}
import java.util.UUID

import org.scalacheck.Shapeless._
import com.ovoenergy.comms.model._
import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}

import shapeless.Coproduct

trait TestGenerators {

  // Ensure we don't get empty strings
  implicit def arbString: Arbitrary[String] = Arbitrary {
    UUID.randomUUID().toString
  }

  implicit def arbDateTime: Arbitrary[OffsetDateTime] = Arbitrary {
    OffsetDateTime.now()
  }

  implicit def arbInstant: Arbitrary[Instant] = Arbitrary {
    generate[OffsetDateTime].toInstant
  }

  implicit def arbMetadata: Arbitrary[Metadata] = {
    val createdAtStr = generate[OffsetDateTime].toString
    Arbitrary {
      Metadata(
        createdAt = createdAtStr,
        eventId = generate[String],
        customerId = generate[String],
        traceToken = generate[String],
        commManifest = generate[CommManifest],
        friendlyDescription = generate[String],
        source = generate[String],
        canary = generate[Boolean],
        sourceMetadata = None,
        triggerSource = generate[String]
      )
    }
  }

  implicit def arbMetadataV2: Arbitrary[MetadataV2] = {
    Arbitrary {
      MetadataV2(
        createdAt = generate[OffsetDateTime].toInstant,
        eventId = generate[String],
        traceToken = generate[String],
        commManifest = generate[CommManifest],
        friendlyDescription = generate[String],
        source = generate[String],
        canary = generate[Boolean],
        sourceMetadata = None,
        triggerSource = generate[String],
        deliverTo = generate[DeliverTo]
      )
    }
  }

  implicit def arbGenericMetadata: Arbitrary[GenericMetadata] = {
    val createdAtStr = generate[OffsetDateTime].toString
    Arbitrary {
      GenericMetadata(
        createdAt = createdAtStr,
        eventId = generate[String],
        traceToken = generate[String],
        source = generate[String],
        canary = generate[Boolean]
      )
    }
  }

  implicit def arbTriggeredV2: Arbitrary[TriggeredV3] = {
    Arbitrary {
      TriggeredV3(
        metadata = generate[MetadataV2],
        templateData = Map("someKey" -> TemplateData(Coproduct[TemplateData.TD]("someValue"))),
        deliverAt = Some(generate[Instant]),
        expireAt = None,
        preferredChannels = None
      )
    }
  }

  def generate[A: Arbitrary] = {
    implicitly[Arbitrary[A]].arbitrary.apply(Gen.Parameters.default.withSize(3), Seed.random()).get
  }
}
