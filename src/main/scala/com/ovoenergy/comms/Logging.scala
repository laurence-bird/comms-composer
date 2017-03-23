package com.ovoenergy.comms

import com.ovoenergy.comms.types.HasMetadata
import org.slf4j.{LoggerFactory, MDC}

trait Logging {

  val log = LoggerFactory.getLogger(getClass)

  private val TraceToken = "traceToken"

  def info[A <: HasMetadata](a: A)(message: String): Unit = {
    withMDC(a)(log.info(message))
  }

  def warn[A <: HasMetadata](a: A)(message: String): Unit = {
    withMDC(a)(log.warn(message))
  }

  def warnE[A <: HasMetadata](a: A)(message: String, exception: Throwable): Unit = {
    withMDC(a)(log.warn(message, exception))
  }

  private def withMDC[A <: HasMetadata](a: A)(block: => Unit): Unit = {
    MDC.put(TraceToken, a.metadata.traceToken)
    try {
      block
    } finally {
      MDC.remove(TraceToken)
    }
  }

}
