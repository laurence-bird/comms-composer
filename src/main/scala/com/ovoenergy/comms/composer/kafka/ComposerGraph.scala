//package com.ovoenergy.comms.composer.kafka
//
//import akka.Done
//import akka.actor.{ActorSystem, Scheduler}
//import akka.kafka.scaladsl.Consumer
//import akka.kafka.scaladsl.Consumer.Control
//import akka.kafka.Subscriptions
//import akka.stream.{ActorAttributes, Supervision}
//import akka.stream.scaladsl.Source
//import com.ovoenergy.comms.composer.{ComposerError, Logging}
//import com.ovoenergy.comms.composer.sms.BuildFailedEventFrom
//import com.ovoenergy.comms.model._
//import org.apache.kafka.clients.producer.RecordMetadata
//
//import scala.concurrent.{ExecutionContext, Future}
//import scala.util.control.NonFatal
//import com.ovoenergy.comms.helpers.Topic
//import com.sksamuel.avro4s.{FromRecord, SchemaFor}
//
//import scala.reflect.ClassTag
//// Implicits
//import com.ovoenergy.comms.serialisation.Codecs._
//import scala.language.reflectiveCalls
//
//object ComposerGraph extends Logging {
//
//  def build[InEvent <: LoggableEvent: SchemaFor: FromRecord: ClassTag, OutEvent <: LoggableEvent](
//      topic: Topic[InEvent],
//      outputProducer: => OutEvent => Future[RecordMetadata],
//      failedProducer: FailedV2 => Future[RecordMetadata])(processEvent: InEvent => Either[ComposerError, OutEvent])(
//      implicit scheduler: Scheduler,
//      actorSystem: ActorSystem,
//      buildFailedEventFrom: BuildFailedEventFrom[InEvent],
//      ec: ExecutionContext): Source[Done, Control] = {
//
//    def sendOutput(event: OutEvent): Future[_] = {
//      outputProducer(event).recover {
//        case NonFatal(e) =>
//          warnT(event)(
//            "Unable to produce event, however, processing has completed so offset will be committed regardless",
//            e)
//      }
//    }
//
//    def sendFailed(failedToComposeError: ComposerError, inEvent: InEvent): Future[_] = {
//      val failed = buildFailedEventFrom(inEvent, failedToComposeError)
//      failedProducer(failed).recover {
//        case NonFatal(e) =>
//          warnT(failed)(
//            "Unable to produce Failed event, however, processing has completed so offset will be committed regardless",
//            e)
//      }
//    }
//
//    val decider: Supervision.Decider = { e =>
//      log.error("Kafka consumer actor exploded!", e)
//      Supervision.Stop
//    }
//
//    val consumerSettings = topic.consumerSettings match {
//      case Left(l) => {
//        log.error(s"Failed to register consumer schema for ${topic.name}. Made ${l.attemptsMade} attempts",
//                  l.finalException)
//        sys.exit(1)
//      }
//      case Right(r) => r
//    }
//
//    Consumer
//      .committableSource(consumerSettings, Subscriptions.topics(topic.name))
//      .withAttributes(ActorAttributes.supervisionStrategy(decider))
//      .mapAsync(1) { msg =>
//        val future: Future[_] = msg.record.value match {
//          case Some(inputEvent: InEvent) =>
//            info(inputEvent)(s"Processing event: $inputEvent")
//            processEvent(inputEvent) match {
//              case Left(failed) =>
//                info(inputEvent)(s"Processing failed, sending failed event")
//                sendFailed(failed, inputEvent)
//              case Right(result) =>
//                sendOutput(result)
//            }
//          case None =>
//            Future.successful(())
//        }
//        future.flatMap { _ =>
//          msg.committableOffset.commitScaladsl()
//        }
//      }
//  }
//}
