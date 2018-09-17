package servicetest

import java.time.{LocalDateTime, ZoneId}

import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.util.IOUtils
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.util.Hash
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers.{withThrowawayConsumerFor, _}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, OptionValues}
import servicetest.util.MockTemplates

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class PrintServiceTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with DockerIntegrationTest
    with MockTemplates
    with Arbitraries {

  behavior of "Composer print service"

  it should "create a ComposedPrint event, for a valid request, archiving the rendered pdf in s3" in {
    createOKDocRaptorResponse()
    withThrowawayConsumerFor(kafkaCluster.composedPrint.v2) { consumer =>
      kafkaCluster.orchestratedPrint.v2.publishOnce(orchestratedPrintEvent, 10.seconds)
      val composedPrintEvent = consumer.pollFor(noOfEventsExpected = 1).head
      val key =
        s"${Hash("composer-service-test")}/${LocalDateTime
          .ofInstant(now, ZoneId.systemDefault())
          .format(dateFormatter)}/${now.toEpochMilli}-${orchestratedPrintEvent.metadata.traceToken}-${orchestratedPrintEvent.internalMetadata.internalTraceToken}.pdf"

      composedPrintEvent.metadata.traceToken shouldBe orchestratedPrintEvent.metadata.traceToken
      composedPrintEvent.internalMetadata.internalTraceToken shouldBe orchestratedPrintEvent.internalMetadata.internalTraceToken
      composedPrintEvent.pdfIdentifier should be(key)
      val s3 = s3Client

      val files: ObjectListing = s3.listObjects("dev-ovo-comms-pdfs")
      files.getObjectSummaries.asScala.foreach(println)

      val s3Object = s3.getObject("dev-ovo-comms-pdfs", key)
      IOUtils.toByteArray(s3Object.getObjectContent) should contain theSameElementsAs (pdfResponseByteArray)
    }
  }

  it should "send a failed event if docRaptor is not available" in {
    create400DocRaptorResponse()
    withThrowawayConsumerFor(kafkaCluster.failed.v3) { consumer =>
      kafkaCluster.orchestratedPrint.v2.publishOnce(orchestratedPrintEvent, 10.seconds)
      val failedEvent = consumer.pollFor(noOfEventsExpected = 1).head

      failedEvent.errorCode should be(CompositionError)
      failedEvent.reason should be("Failed to render pdf: Bad Request")
    }
  }


}
