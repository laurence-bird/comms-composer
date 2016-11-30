package com.ovoenergy.comms

import org.slf4j.{LoggerFactory, MDC}

trait Logging {

  val log = LoggerFactory.getLogger(getClass)

  private val TraceToken = "traceToken"

  def info(traceToken: String)(message: String): Unit = {
    withMDC(traceToken)(log.info(message))
  }

  def warn(traceToken: String)(message: String): Unit = {
    withMDC(traceToken)(log.warn(message))
  }

  def warnE(traceToken: String)(message: String, exception: Throwable): Unit = {
    withMDC(traceToken)(log.warn(message, exception))
  }

  private def withMDC(traceToken: String)(block: => Unit): Unit = {
    MDC.put(TraceToken, traceToken)
    try {
      block
    } finally {
      MDC.remove(TraceToken)
    }
  }

}
