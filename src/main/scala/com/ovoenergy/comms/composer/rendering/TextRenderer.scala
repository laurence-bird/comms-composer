package com.ovoenergy.comms.composer
package rendering

import java.time.ZonedDateTime
import java.util.{Map => JMap}
import cats.data.EitherT

import shapeless._
import cats._
import cats.data._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

import com.github.jknack.handlebars
import com.github.jknack.handlebars.helper.DefaultHelperRegistry
import com.github.jknack.handlebars.io.StringTemplateSource
import com.github.jknack.handlebars.ValueResolver

import com.ovoenergy.comms.model.{TemplateData, MissingTemplateData}

import model.{TemplateFragmentId, RenderedFragment, ComposerError}

trait TextRenderer[F[_]] {
  def render(id: TemplateFragmentId, data: TemplateData): F[Option[RenderedFragment]]
}

object TextRenderer {

  def apply[F[_]: Concurrent](templates: Templates[F]): F[TextRenderer[F]] = {

    // It is wrapped in F as its instatiation may have side effect
    val handlebarsEngineF: F[handlebars.Handlebars] = Sync[F].delay(
      new handlebars.Handlebars()
        .registerHelperMissing(MissingKeys.helper[AnyRef]))

    val cacheF: F[Memoize[F, TemplateFragmentId, Option[handlebars.Template]]] =
      handlebarsEngineF.flatMap { handlebarsEngine =>
        Memoize[F, TemplateFragmentId, Option[handlebars.Template]] { id =>
          OptionT(templates.loadTemplateFragment(id))
            .semiflatMap(tf => Sync[F].delay(handlebarsEngine.compileInline(tf.value)))
            .value
        }
      }

    cacheF.map { cache =>
      new TextRenderer[F] {
        def render(id: TemplateFragmentId, data: TemplateData): F[Option[RenderedFragment]] = {
          OptionT(cache.get(id)).semiflatMap { template =>
            // NOTE:
            // The context needs to be destroied as it may contain circular
            // dependency and causing memory leak

            val contextF = handlebars.Context
              .newBuilder(data)
              .resolver(new TemplateDataValueResolver)
              .build()
              .pure[F]

            contextF.bracket { context =>
              val fillTemplate = Sync[F].delay(template(context))

              def produceResult(filledTemplate: String) = {
                val missingKeys = MissingKeys.missingKeysFor(context)
                if (missingKeys.nonEmpty) {
                  val missingKeysAsString = missingKeys.mkString("[", ",", "]")
                  new ComposerError(
                    s"The template referenced the following non-existent keys: $missingKeysAsString",
                    MissingTemplateData
                  ).raiseError[F, RenderedFragment]
                } else {
                  RenderedFragment(filledTemplate).pure[F]
                }
              }

              fillTemplate.flatMap(produceResult)

            }(context => Sync[F].delay(context.destroy()))
          }.value
        }
      }
    }
  }
}
