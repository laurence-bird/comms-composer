package com.ovoenergy.comms.composer

import com.ovoenergy.comms.model.LoggableEvent
import org.slf4j.{Logger, LoggerFactory, MDC}

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
