package com.ovoenergy.comms

import org.slf4j.{LoggerFactory, MDC}

trait Logging {

  val log = LoggerFactory.getLogger(getClass)

  private val TransactionId = "transactionId"

  def info(transactionId: String)(message: String): Unit = {
    withMDC(transactionId)(log.info(message))
  }

  def warn(transactionId: String)(message: String): Unit = {
    withMDC(transactionId)(log.warn(message))
  }

  def warnE(transactionId: String)(message: String, exception: Throwable): Unit = {
    withMDC(transactionId)(log.warn(message, exception))
  }

  private def withMDC(transactionId: String)(block: => Unit): Unit = {
    MDC.put(TransactionId, transactionId)
    try {
      block
    } finally {
      MDC.remove(TransactionId)
    }
  }

}
