package com.ovoenergy.comms.composer

import cats.{Contravariant, Show, Traverse}
import cats.syntax.all._
import com.ovoenergy.comms.model.LoggableEvent
import org.slf4j.{Logger, LoggerFactory, MDC}

import scala.language.higherKinds

trait Logging {

  val log: Logger = LoggerFactory.getLogger(getClass)

  private def loggableEventToString(event: LoggableEvent): String = {
    event.loggableString.map(ls => s": \n$ls").getOrElse("")
  }

  def debug[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.debug(message))
  }

  def info[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.info(message))
  }

  def infoE[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.info(s"$message${loggableEventToString(a)}"))
  }

  def warn[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.warn(message))
  }

  def warnT[A <: LoggableEvent](a: A)(message: String, throwable: Throwable): Unit = {
    withMDC(a)(log.warn(message, throwable))
  }

  private def withMDC[A <: LoggableEvent](a: A)(block: => Unit): Unit = {
    a.mdcMap.foreach { case (mdcParam, mdcValue) => MDC.put(mdcParam, mdcValue) }
    try {
      block
    } finally {
      a.mdcMap.foreach { case (mdcParam, _) => MDC.remove(mdcParam) }
    }
  }
}

trait Loggable[A] {

  def mdcMap(a: A): Map[String, String]

}

object Loggable {

  case class Prefixed[A](prefix: String, nested: A)

  case class Capitalized[A](nested: A)

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
    override def contramap[A, B](fa: Loggable[A])(f: B => A): Loggable[B] = instance[B](b => fa.mdcMap(f(b)))
  }

  implicit val stringStringLoggable: Loggable[(String, String)] =
    instance(a => Map(a))

  implicit def stringShowLoggable[A: Show]: Loggable[(String, A)] =
    instance {
      case (k, v) =>
        Map(k -> v.show)
    }

  implicit def mapLoggable[A, B](implicit abLoggable: Loggable[(A, B)]): Loggable[Map[A, B]] = instance { xs =>
    xs.foldLeft(Map.empty[String, String]) { (s, x) =>
      s ++ abLoggable.mdcMap(x)
    }
  }

  implicit def traverseLoggable[M[_]: Traverse, A: Loggable]: Loggable[M[A]] = instance { xs =>
    xs.foldLeft(Map.empty[String, String]) { (s, x) =>
      s ++ Loggable[A].mdcMap(x)
    }
  }

  implicit def prefixedLoggable[A](implicit aLoggable: Loggable[A]): Loggable[Prefixed[A]] = instance {
    case Prefixed(prefix, a) =>
      aLoggable.mdcMap(a).map {
        case (k, v) =>
          s"$prefix$k" -> v
      }
  }

  implicit def capitalizedLoggable[A](implicit aLoggable: Loggable[A]): Loggable[Capitalized[A]] = instance {
    case Capitalized(a) =>
      aLoggable.mdcMap(a).map {
        case (k, v) =>
          k.capitalize -> v
      }
  }

  implicit def tuple1Loggable[A](implicit aLoggable: Loggable[A]): Loggable[Tuple1[A]] = aLoggable.contramap(_._1)

  implicit def tuple2Loggable[A1, A2](implicit a1Loggable: Loggable[A1],
                                      a2Loggable: Loggable[A2]): Loggable[(A1, A2)] =
    instance {
      case (a1, a2) =>
        a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2)
    }

  implicit def tuple3Loggable[A1, A2, A3](implicit a1Loggable: Loggable[A1],
                                          a2Loggable: Loggable[A2],
                                          a3Loggable: Loggable[A3]): Loggable[(A1, A2, A3)] = instance {
    case (a1, a2, a3) =>
      a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2) ++ a3Loggable.mdcMap(a3)
  }

  implicit def tuple4Loggable[A1, A2, A3, A4](implicit a1Loggable: Loggable[A1],
                                              a2Loggable: Loggable[A2],
                                              a3Loggable: Loggable[A3],
                                              a4Loggable: Loggable[A4]): Loggable[(A1, A2, A3, A4)] = instance {
    case (a1, a2, a3, a4) =>
      a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2) ++ a3Loggable.mdcMap(a3) ++ a4Loggable.mdcMap(a4)
  }

  implicit def tuple5Loggable[A1, A2, A3, A4, A5](implicit a1Loggable: Loggable[A1],
                                                  a2Loggable: Loggable[A2],
                                                  a3Loggable: Loggable[A3],
                                                  a4Loggable: Loggable[A4],
                                                  a5Loggable: Loggable[A5]): Loggable[(A1, A2, A3, A4, A5)] =
    instance {
      case (a1, a2, a3, a4, a5) =>
        a1Loggable.mdcMap(a1) ++ a2Loggable.mdcMap(a2) ++ a3Loggable.mdcMap(a3) ++ a4Loggable.mdcMap(a4) ++ a5Loggable
          .mdcMap(a5)
    }

}
