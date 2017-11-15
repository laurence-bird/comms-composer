package com.ovoenergy.comms.composer.http

import java.util.concurrent.TimeUnit

import com.ovoenergy.comms.composer.http.Retry.RetryConfig
import okhttp3.{OkHttpClient, Request, Response}

import scala.util.Try

object HttpClient {

  private val httpClient = new OkHttpClient()
    .newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  def apply(request: Request): Try[Response] = {
    Try(httpClient.newCall(request).execute())
  }
}
