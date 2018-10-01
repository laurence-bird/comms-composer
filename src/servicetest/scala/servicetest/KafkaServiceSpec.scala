package com.ovoenergy.comms.composer
package servicetest

import com.ovoenergy.comms.model._
import email.OrchestratedEmailV4

import org.apache.kafka.clients.producer.ProducerRecord
import cats.implicits._
import cats.effect.IO

import com.ovoenergy.fs2.kafka._


class KafkaServiceSpec extends ServiceSpec with TestGenerators {

  "Test" should {
    "be able to produce and consume on topics" in {

      val message = generate[OrchestratedEmailV4]

      val result = withProducerFor(topics.orchestratedEmail) { producer =>
        produceRecord[IO](
          producer,
          new ProducerRecord[String, OrchestratedEmailV4](
            topics.orchestratedEmail.name,
            message.metadata.commId,
            message)
        ) *> consume(topics.orchestratedEmail) { r =>
          IO.pure(r)
        }.head.compile.lastOrRethrow
      }.futureValue

      result.value() shouldBe message
    }
  }

}
