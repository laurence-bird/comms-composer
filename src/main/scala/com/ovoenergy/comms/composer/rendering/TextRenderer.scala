package com.ovoenergy.comms.composer
package rendering

import java.time.ZonedDateTime
import java.util.{Map => JMap}
import cats.data.EitherT

import cats._
import cats.data._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

import com.github.jknack.handlebars
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.StringTemplateSource
import com.github.jknack.handlebars.{Helper, Handlebars, Options => HbOpt, Context => HbContext}

import com.ovoenergy.comms.model.{TemplateData, MissingTemplateData}

import model.{TemplateFragmentId, RenderedFragment, ComposerError}

trait TextRenderer[F[_]] {
  def render(id: TemplateFragmentId, data: Map[String, TemplateData]): F[Option[RenderedFragment]]
}

object TextRenderer {

  case class ComposerContext() extends handlebars.Context {

    def addMissingKey(missingKey: String): ComposerContext = {
      ???
      this
    }

    def missingKeys: Set[String] = {
      ???
    }
  }

  def apply[F[_]: Concurrent](templates: Templates[F]): F[TextRenderer[F]] = {

    val helperRegistry = {
      val reg = new DefaultHelperRegistry()
      reg.registerHelperMissing(new Helper[ComposerContext] {
        override def apply(context: ComposerContext, options: handlebars.Options): AnyRef = {
          // TODO collect missing keys
          // missingKeys.add(options.helperName)
          context.addMissingKey(options.helperName)
          ""
        }
      })
      reg
    }

    val handlebarsEngine = new handlebars.Handlebars().`with`(helperRegistry)

    val cacheF: F[Memoize[F, TemplateFragmentId, Option[handlebars.Template]]] =
      Memoize[F, TemplateFragmentId, Option[handlebars.Template]] { id =>
        OptionT(templates.loadTemplateFragment(id))
          .map(tf => handlebarsEngine.compileInline(tf.value))
          .value
      }

    cacheF.map { cache =>
      new TextRenderer[F] {
        def render(
            id: TemplateFragmentId,
            data: Map[String, TemplateData]): F[Option[RenderedFragment]] = {
          OptionT(cache.get(id)).semiflatMap { template =>
            Sync[F].delay {
              // TODO Build a ComposerContext please
              val context: ComposerContext = ??? // handlebars.Context.newBuilder(data)
              val result = RenderedFragment(template(context))

              if (context.missingKeys.nonEmpty) {
                val missingKeysAsString = context.missingKeys.mkString("[", ",", "]")
                throw new ComposerError(
                  s"The template referenced the following non-existent keys: $missingKeysAsString",
                  MissingTemplateData
                )
              } else {
                result
              }
            }
          }.value
        }
      }
    }
  }
}
