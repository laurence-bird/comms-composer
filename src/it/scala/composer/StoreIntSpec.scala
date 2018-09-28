package com.ovoenergy.comms.composer

import model._
import model.Email.Subject

import com.ovoenergy.comms.aws._
import common.CredentialsProvider
import common.model._
import s3.S3
import s3.model._

import cats.implicits._
import cats.effect.IO

import fs2._
import fs2.Stream.ToEffect

import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze._
import org.http4s.client.middleware.{ResponseLogger, RequestLogger}

import java.util.UUID

class StoreIntSpec extends IntegrationSpec {

  private val existingBucket = Bucket("ovo-comms-test")
  private val region = Region.`eu-west-1`

  def constantKeys(key: Key): Store.Keys[IO] = new Store.Keys[IO] {
    override def get(commId: CommId, traceToken: TraceToken): IO[Key] = key.pure[IO]
  }

  implicit class RichToEffectIO[O](te: ToEffect[IO, O]) {
    def lastOrRethrow: IO[O] =
      te.last
        .map(_.toRight[Throwable](new IllegalStateException("Empty Stream")))
        .rethrow

  }

  "Store" should {
    "store fragment and return valid s3 URI" in {

      val commId = UUID.randomUUID().toString
      val traceToken = UUID.randomUUID().toString

      val fragment = Subject("This is a good news")

      val key = Key(s"$commId/${UUID.randomUUID().toString}")

      withS3 { s3 =>
        val store = Store[IO](s3, Store.Config(existingBucket), constantKeys(key))
        store.upload(commId, traceToken, fragment)
      }.futureValue shouldBe Uri.unsafeFromString(s"https://ovo-comms-test.s3-eu-west-1.amazonaws.com/${key.value}")
    }

    "store fragment with metadata" in {

      val commId = UUID.randomUUID().toString
      val traceToken = UUID.randomUUID().toString

      val fragment = Subject("This is a good news")

      val key = Key(s"$commId/${UUID.randomUUID().toString}")

      withS3 { s3 =>
        val store = Store[IO](s3, Store.Config(existingBucket), constantKeys(key))
        for {
          _ <- store.upload(commId, traceToken, fragment)
          retrieved <- s3.headObject(existingBucket, key).map(_.leftWiden[Throwable]).rethrow
        } yield retrieved
      }.futureValue.metadata shouldBe Map("comm-id" -> commId, "trace-token" -> traceToken)
    }

    "store fragment that can be retrieved" in {

      val commId = UUID.randomUUID().toString
      val traceToken = UUID.randomUUID().toString

      val fragment = Subject("This is a good news")

      val key = Key(s"$commId/${UUID.randomUUID().toString}")

      withS3 { s3 =>
        val store = Store[IO](s3, Store.Config(existingBucket), constantKeys(key))
        for {
          _ <- store.upload(commId, traceToken, fragment)
          retrieved <- s3.getObject(existingBucket, key)
            .map(_.leftWiden[Throwable])
            .rethrow
            .flatMap(_.content.through(text.utf8Decode).compile.lastOrRethrow)
        } yield retrieved
      }.futureValue shouldBe fragment.content
    }
  }

  def withS3[A](f: S3[IO] => IO[A]): IO[A] = {
    Http1Client
      .stream[IO]()
      .map { client =>

        val responseLogger: Client[IO] => Client[IO] = ResponseLogger.apply0[IO](logBody = true, logHeaders = true)
        val requestLogger: Client[IO] => Client[IO] = RequestLogger.apply0[IO](logBody = false, logHeaders = true, redactHeadersWhen = _ => false)
        val loggingClient = responseLogger(requestLogger(client))

        new S3[IO](loggingClient, CredentialsProvider.default[IO], region)
      }
      .evalMap(f)
      .compile.lastOrRethrow
  }


}
