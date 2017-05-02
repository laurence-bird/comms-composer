package com.ovoenergy.comms

import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.comms.types.HasMetadata
import org.slf4j.{Logger, LoggerFactory, MDC}

trait Logging {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def debug[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.debug(appendEvent(message, a)))
  }

  def info[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.info(appendEvent(message, a)))
  }

  def warn[A <: LoggableEvent](a: A)(message: String): Unit = {
    withMDC(a)(log.warn(appendEvent(message, a)))
  }

  def warnE[A <: LoggableEvent](a: A)(message: String, exception: Throwable): Unit = {
    withMDC(a)(log.warn(appendEvent(message, a), exception))
  }

  private def appendEvent[A <: LoggableEvent](message: String, a: A): String = {
    message + a.loggableString.map(ls => s"\n$ls").getOrElse("")
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
