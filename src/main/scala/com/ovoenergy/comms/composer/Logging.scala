package com.ovoenergy.comms.composer

import fs2.kafka._
import cats.{Contravariant, Show, Traverse}
import cats.syntax.all._

import org.slf4j.{Logger, LoggerFactory, MDC}

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.RecordMetadata

import com.amazonaws.auth.{AWSCredentialsProvider, DefaultAWSCredentialsProviderChain}
import com.amazonaws.regions.{AwsRegionProvider, DefaultAwsRegionProviderChain}

import com.ovoenergy.comms.model.{CommManifest, LoggableEvent, TemplateManifest}
trait Logging {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def debug[A: Loggable](a: A)(message: => String): Unit = {
    withMDC(a)(log.debug(message))
  }

  def debug(message: => String): Unit = {
    log.debug(message)
  }

  def info[A: Loggable](a: A)(message: => String): Unit = {
    withMDC(a)(log.info(message))
  }

  def info(message: => String): Unit = {
    log.info(message)
  }

  def warn[A: Loggable](a: A)(message: => String): Unit = {
    withMDC(a)(log.warn(message))
  }

  def warn(message: => String): Unit = {
    log.warn(message)
  }

  def warnWithException[A: Loggable](a: A)(message: => String)(throwable: Throwable): Unit = {
    withMDC(a -> throwable)(log.warn(message, throwable))
  }

  def warnWithException(message: => String)(throwable: Throwable): Unit = {
    withMDC(throwable)(log.warn(message, throwable))
  }

  def fail[A: Loggable](a: A)(message: => String): Unit = {
    withMDC(a)(log.error(message))
  }
  def failWithException[A: Loggable](a: A)(message: => String)(throwable: Throwable): Unit = {
    withMDC(a -> throwable)(log.error(message, throwable))
  }

  def failWithException(message: => String)(throwable: Throwable): Unit = {
    withMDC(throwable)(log.error(message, throwable))
  }

  def fail(message: => String): Unit = {
    log.error(message)
  }

  private def withMDC[A: Loggable, B](a: A)(block: => B): B = {
    val A = implicitly[Loggable[A]]

    try {
      A.mdcMap(a).foreach { case (mdcParam, mdcValue) => MDC.put(mdcParam, mdcValue) }
      block
    } finally {
      A.mdcMap(a).foreach { case (mdcParam, _) => MDC.remove(mdcParam) }
    }
  }
}

trait Loggable[A] {

  def mdcMap(a: A): Map[String, String]

}

object Loggable {

  case class Prefixed[A](prefix: String, nested: A)

  case class Capitalized[A](nested: A)

  def logContext[A](a: A)(implicit la: Loggable[A]): Seq[(String, String)] = {
    la.mdcMap(a).toSeq
  }

  def logContext[A1, A2](a1: A1, a2: A2)(
      implicit la1: Loggable[A1],
      la2: Loggable[A2]): Seq[(String, String)] = {
    (la1.mdcMap(a1) ++ la2.mdcMap(a2)).toSeq
  }

  def logContext[A1, A2, A3](a1: A1, a2: A2, a3: A3)(
      implicit la1: Loggable[A1],
      la2: Loggable[A2],
      la3: Loggable[A3]): Seq[(String, String)] = {
    (la1.mdcMap(a1) ++ la2.mdcMap(a2) ++ la3.mdcMap(a3)).toSeq
  }

  /**
    * Prefix the logged keys with the given prefix.
    *
    * example:
    *
    * info(prefix("little_", Map("foo"->"bar")))
    *
    * will log:
    *
    *   little_foo = bar
    *
    */
  def prefix[A](prefix: String, a: A): Prefixed[A] = Prefixed(prefix, a)

  /**
    * Capitalize the logged keys.
    *
    * You can combine this with prefix to have prefixed camel case keys
    *
    * example:
    *
    * info(capitalize(Map("foo"->"bar")))
    *
    * will log:
    *
    *   Foo = bar
    *
    * info(prefix("little", capitalize(Map("foo"->"bar"))))
    *
    * will log:
    *
    *   littleFoo = bar
    */
  def capitalize[A](a: A): Capitalized[A] = Capitalized(a)

  def apply[A: Loggable]: Loggable[A] = implicitly[Loggable[A]]

  def instance[A](f: A => Map[String, String]): Loggable[A] = new Loggable[A] {
    override def mdcMap(a: A): Map[String, String] = f(a)
  }

  def mdcMap[A: Loggable](a: A): Map[String, String] = Loggable[A].mdcMap(a)

  implicit def throwableLoggable: Loggable[Throwable] = instance { throwable =>
    Map(
      "exceptionClass" -> throwable.getClass.getName
    )
  }

  implicit def catsInstancesForLoggable: Contravariant[Loggable] = new Contravariant[Loggable] {
    override def contramap[A, B](fa: Loggable[A])(f: B => A): Loggable[B] =
      instance[B](b => fa.mdcMap(f(b)))
  }

  implicit val stringStringLoggable: Loggable[(String, String)] =
    instance(a => Map(a))

  implicit def stringShowLoggable[A: Show]: Loggable[(String, A)] =
    instance {
      case (k, v) =>
        Map(k -> v.show)
    }

  implicit def mapLoggable[A, B](implicit abLoggable: Loggable[(A, B)]): Loggable[Map[A, B]] =
    instance { xs =>
      xs.foldLeft(Map.empty[String, String]) { (s, x) =>
        s ++ abLoggable.mdcMap(x)
      }
    }

  implicit def traverseLoggable[M[_]: Traverse, A: Loggable]: Loggable[M[A]] = instance { xs =>
    xs.foldLeft(Map.empty[String, String]) { (s, x) =>
      s ++ Loggable[A].mdcMap(x)
    }
  }

  implicit def prefixedLoggable[A](implicit aLoggable: Loggable[A]): Loggable[Prefixed[A]] =
    instance {
      case Prefixed(prefix, a) =>
        aLoggable.mdcMap(a).map {
          case (k, v) =>
            s"$prefix$k" -> v
        }
    }

  implicit def capitalizedLoggable[A](implicit aLoggable: Loggable[A]): Loggable[Capitalized[A]] =
    instance {
      case Capitalized(a) =>
        aLoggable.mdcMap(a).map {
          case (k, v) =>
            k.capitalize -> v
        }
    }

  implicit def tuple1Loggable[A](implicit aLoggable: Loggable[A]): Loggable[Tuple1[A]] =
    aLoggable.contramap(_._1)

  implicit def tuple2Loggable[A1, A2](
      implicit a1Loggable: Loggable[A1],
      a2Loggable: Loggable[A2]): Loggable[(A1, A2)] =
    instance {
      case (a1, a2) =>
        a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2)
    }

  implicit def tuple3Loggable[A1, A2, A3](
      implicit a1Loggable: Loggable[A1],
      a2Loggable: Loggable[A2],
      a3Loggable: Loggable[A3]): Loggable[(A1, A2, A3)] = instance {
    case (a1, a2, a3) =>
      a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2) ++ a3Loggable.mdcMap(a3)
  }

  implicit def tuple4Loggable[A1, A2, A3, A4](
      implicit a1Loggable: Loggable[A1],
      a2Loggable: Loggable[A2],
      a3Loggable: Loggable[A3],
      a4Loggable: Loggable[A4]): Loggable[(A1, A2, A3, A4)] = instance {
    case (a1, a2, a3, a4) =>
      a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2) ++ a3Loggable.mdcMap(a3) ++ a4Loggable.mdcMap(
        a4)
  }

  implicit def tuple5Loggable[A1, A2, A3, A4, A5](
      implicit a1Loggable: Loggable[A1],
      a2Loggable: Loggable[A2],
      a3Loggable: Loggable[A3],
      a4Loggable: Loggable[A4],
      a5Loggable: Loggable[A5]): Loggable[(A1, A2, A3, A4, A5)] =
    instance {
      case (a1, a2, a3, a4, a5) =>
        a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2) ++ a3Loggable.mdcMap(a3) ++ a4Loggable
          .mdcMap(a4) ++ a5Loggable
          .mdcMap(a5)
    }

  implicit def loggableEventLoggable[A <: LoggableEvent]: Loggable[A] = Loggable.instance(_.mdcMap)

  implicit def commManifestLoggable: Loggable[CommManifest] = Loggable.instance { cm =>
    Map(
      "commName" -> cm.name,
      "commVersion" -> cm.version
    )
  }

  implicit def templateManifestLoggable: Loggable[TemplateManifest] = Loggable.instance { tm =>
    Map(
      "templateId" -> tm.id,
      "templateVersion" -> tm.version
    )
  }

  implicit def loggableRecord[K, V](
      implicit valueLoggable: Loggable[V]): Loggable[ConsumerRecord[K, V]] =
    Loggable.instance[ConsumerRecord[K, V]] { consumerRecord =>
      Map(
        "kafkaTopic" -> consumerRecord.topic(),
        "kafkaPartition" -> consumerRecord.partition().toString,
        "kafkaOffset" -> consumerRecord.offset().toString
      ) ++ valueLoggable.mdcMap(consumerRecord.value)
    }

  implicit def loggableRecordMetadata: Loggable[RecordMetadata] =
    Loggable.instance[RecordMetadata] { recordMetadata =>
      Map(
        "kafkaTopic" -> recordMetadata.topic(),
        "kafkaPartition" -> recordMetadata.partition().toString,
        "kafkaOffset" -> recordMetadata.offset().toString
      )
    }

  implicit def loggableProducerRecord[K, V](
      implicit vl: Loggable[V]): Loggable[ProducerRecord[K, V]] = Loggable.instance { pr =>
    vl.mdcMap(pr.value)
  }

}
