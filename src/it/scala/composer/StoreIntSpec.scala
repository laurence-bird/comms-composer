package com.ovoenergy.comms.composer

import model._
import model.Email.Subject
import com.ovoenergy.comms.aws._
import common.CredentialsProvider
import common.model._
import s3.S3
import s3.model._
import cats.implicits._
import cats.effect.{IO, Timer}
import fs2._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze._
import org.http4s.client.middleware.{RequestLogger, ResponseLogger}
import java.util.UUID

import scala.concurrent.ExecutionContext

class StoreIntSpec extends IntegrationSpec {

  private val existingBucket = Bucket("ovo-comms-test")
  private val region = Region.`eu-west-1`

  def constantKeys(key: Key): Store.Keys[IO] = new Store.Keys[IO] {
    override def get(commId: CommId, traceToken: TraceToken): IO[Key] = key.pure[IO]
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
            .flatMap(_.content.through(text.utf8Decode).compile.lastOrError)
        } yield retrieved
      }.futureValue shouldBe fragment.content
    }
  }

  def withS3[A](f: S3[IO] => IO[A]): IO[A] = {

    implicit val ec = ExecutionContext.global
    implicit val contextShift = cats.effect.IO.contextShift(ec)
    implicit val timer: Timer[IO] = IO.timer(ec)

    S3.resource[IO](CredentialsProvider.default[IO], region).use(f)
  }


}
