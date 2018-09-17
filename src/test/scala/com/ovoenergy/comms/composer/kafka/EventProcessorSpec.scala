package com.ovoenergy.comms.composer.kafka

import cats.effect.IO
import com.ovoenergy.comms.composer
import com.ovoenergy.comms.composer.Main.Record
import com.ovoenergy.comms.composer.{ComposerError, TestGenerators}
import com.ovoenergy.comms.composer.kafka.BuildFeedback.AllFeedback
import com.ovoenergy.comms.model._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalacheck.Arbitrary
import org.scalatest.{FlatSpec, Matchers}

class EventProcessorSpec extends FlatSpec with Matchers with Arbitraries with TestGenerators {

  case class InputEvent(thing: String) extends LoggableEvent {
    override def loggableString: Option[String] = None
    override def mdcMap: Map[String, String] = Map.empty[String, String]
  }
  implicit val arbInputEvent = Arbitrary(genNonEmptyString.map(InputEvent))

  case class OutputEvent(yo: String) extends LoggableEvent {
    override def loggableString: Option[String] = None
    override def mdcMap: Map[String, String] = Map.empty[String, String]
  }
  implicit val arbOutputEvent = Arbitrary(genNonEmptyString.map(OutputEvent))

  implicit val buildFeedbackFrom = new BuildFeedback[InputEvent] {
    override def apply(inEvent: InputEvent, error: composer.ComposerError): AllFeedback = {
      AllFeedback(
        generate[FailedV3],
        generate[Feedback]
      )
    }
  }
  case class TestProducer[Output](result: RecordMetadata, function: Output => IO[RecordMetadata])

  def successfulProducer[Output] = {
    val result = new RecordMetadata(
      new TopicPartition(generate[String], generate[Int]),
      generate[Long],
      generate[Long],
      generate[Long],
      generate[Long],
      generate[Int],
      generate[Int])
    val func = (output: Output) => IO(result)
    TestProducer(result, func)
  }

  it should "Produce a single output in case of success" in {
    val inputEvent = generate[InputEvent]
    val successfulOutputProducer = successfulProducer[OutputEvent]
    val successfulFailedProducer = successfulProducer[FailedV3]
    val successfulFeedbackProducer = successfulProducer[Feedback]

    val successfulProcessEvent = (input: InputEvent) => Right(OutputEvent(generate[String]))
    val eventProcessor: Record[InputEvent] => IO[Seq[RecordMetadata]] =
      EventProcessor[IO, InputEvent, OutputEvent](
        successfulOutputProducer.function,
        successfulFailedProducer.function,
        successfulFeedbackProducer.function,
        successfulProcessEvent)

    val record = new ConsumerRecord[Unit, Option[InputEvent]]("input", 1, 1, (), Some(inputEvent))

    eventProcessor(record).unsafeRunSync() should contain only successfulOutputProducer.result
  }

  it should "Produce both to legacy, and new feedback topic in case of failure" in {
    val inputEvent = generate[InputEvent]
    val successfulOutputProducer = successfulProducer[OutputEvent]
    val successfulFailedProducer = successfulProducer[FailedV3]
    val successfulFeedbackProducer = successfulProducer[Feedback]

    val failingProcessEvent = (input: InputEvent) => Left(ComposerError(generate[String], generate[ErrorCode]))
    val eventProcessor: Record[InputEvent] => IO[Seq[RecordMetadata]] =
      EventProcessor[IO, InputEvent, OutputEvent](
        successfulOutputProducer.function,
        successfulFailedProducer.function,
        successfulFeedbackProducer.function,
        failingProcessEvent)

    val record = new ConsumerRecord[Unit, Option[InputEvent]]("input", 1, 1, (), Some(inputEvent))

    eventProcessor(record).unsafeRunSync() should contain theSameElementsAs Seq(
      successfulFailedProducer.result,
      successfulFeedbackProducer.result)
  }

  it should "Skip an event in the case it is unable to deserialise" in {
    val inputEvent = generate[InputEvent]
    val successfulOutputProducer = successfulProducer[OutputEvent]
    val successfulFailedProducer = successfulProducer[FailedV3]
    val successfulFeedbackProducer = successfulProducer[Feedback]

    val successfulProcessEvent = (input: InputEvent) => Right(OutputEvent(generate[String]))
    val eventProcessor: Record[InputEvent] => IO[Seq[RecordMetadata]] =
      EventProcessor[IO, InputEvent, OutputEvent](
        successfulOutputProducer.function,
        successfulFailedProducer.function,
        successfulFeedbackProducer.function,
        successfulProcessEvent)

    val record = new ConsumerRecord[Unit, Option[InputEvent]]("input", 1, 1, (), None)

    eventProcessor(record).unsafeRunSync() shouldBe empty
  }

}
