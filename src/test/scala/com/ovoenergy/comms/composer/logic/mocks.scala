package com.ovoenergy.comms.composer
package logic

import java.{util => ju}

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.implicits._

import org.http4s.Uri

import org.scalacheck._

import com.ovoenergy.comms.model.sms.OrchestratedSMSV3
import com.ovoenergy.comms.model.TemplateData

import model._
import rendering._

import Arbitraries._

object mocks {

  object MockedPdfRendering {
    def apply(rfs: (RenderedFragment, RenderedPdfFragment)*) = new PdfRendering[IO] {
      val map = rfs.toMap

      def render(textFragment: RenderedFragment, toWatermark: Boolean): IO[RenderedPdfFragment] = {
        map
          .get(textFragment)
          .fold(IO.raiseError[RenderedPdfFragment](new Exception("Cannot render fragment to PDF")))(
            _.pure[IO])
      }
    }

    def empty = apply()

    def failing(ex: Throwable) = new PdfRendering[IO] {
      def render(textFragment: RenderedFragment, toWatermark: Boolean): IO[RenderedPdfFragment] = {
        ex.raiseError[IO, RenderedPdfFragment]
      }
    }
  }

  object MockedStore {
    def apply[A: Fragment](xs: (A, Uri)*) = new Store[IO] {

      val map: Map[List[Byte], Uri] = xs.map {
        case (k, v) =>
          val key = Fragment[A].content(k).compile.toList

          key -> v
      }.toMap

      def upload[A: Fragment](commId: CommId, traceToken: TraceToken, fragment: A): IO[Uri] = {

        val content = Fragment[A].content(fragment).compile.toList

        map
          .get(content)
          .fold(IO.raiseError[Uri](new NoSuchElementException("Uri for fragment not found")))(
            _.pure[IO])
      }
    }

    def failing(ex: Throwable) = new Store[IO] {
      override def upload[A: Fragment](
          commId: CommId,
          traceToken: TraceToken,
          fragment: A): IO[Uri] =
        IO.raiseError(ex)
    }
  }

  object MockedTextRenderer {
    val empty = new TextRenderer[IO] {
      def render(id: TemplateFragmentId, data: TemplateData): IO[Option[RenderedFragment]] = {
        none[RenderedFragment].pure[IO]
      }
    }

    def apply(xs: (TemplateFragmentId, RenderedFragment)*) = new TextRenderer[IO] {

      val map = xs.toMap

      def render(id: TemplateFragmentId, data: TemplateData): IO[Option[RenderedFragment]] = {
        map.get(id).pure[IO]
      }
    }

    def failing(ex: Throwable) = new TextRenderer[IO] {
      def render(id: TemplateFragmentId, data: TemplateData): IO[Option[RenderedFragment]] =
        IO.raiseError(ex)
    }
  }

  case class MockedTextRenderer(items: Map[TemplateFragmentId, RenderedFragment])
      extends TextRenderer[IO] {

    def render(id: TemplateFragmentId, data: TemplateData): IO[Option[RenderedFragment]] = {
      items.get(id).pure[IO]
    }

    def withRenderedFragment(id: TemplateFragmentId, rf: RenderedFragment): MockedTextRenderer = {
      copy(items = items + (id -> rf))
    }

  }
}
