package com.ovoenergy.comms.kafka

import akka.Done
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.scaladsl.Source
import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.{ComposedEmail, Failed, OrchestratedEmail}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object Kafka {

  val log = LoggerFactory.getLogger(getClass)

  case class Input[T](topic: String, consumerSettings: ConsumerSettings[String, T])
  case class Output[T](topic: String, producer: KafkaProducer[String, T])

  def buildStream(input: Input[Option[OrchestratedEmail]],
                  composedEmailEventOutput: Output[ComposedEmail],
                  failedEventOutput: Output[Failed])(
      processEvent: OrchestratedEmail => Either[Failed, ComposedEmail]): Source[Done, Control] = {

    def sendComposedEmail(composedEmail: ComposedEmail): Future[RecordMetadata] =
      composedEmailEventOutput.producer.send(new ProducerRecord(composedEmailEventOutput.topic, composedEmail))

    def sendFailed(failed: Failed): Future[RecordMetadata] =
      failedEventOutput.producer.send(new ProducerRecord(failedEventOutput.topic, failed))

    Consumer.committableSource(input.consumerSettings, Subscriptions.topics(input.topic)).mapAsync(1) { msg =>
      val future: Future[_] = msg.record.value match {
        case Some(orchestratedEmail) =>
          log.info(s"Processing event: $orchestratedEmail")
          processEvent(orchestratedEmail) match {
            case Left(failed) => sendFailed(failed)
            case Right(result) => sendComposedEmail(result)
          }
        case None =>
          Future.successful(())
      }
      future flatMap { _ =>
        msg.committableOffset.commitScaladsl()
      }
    }
  }

}
