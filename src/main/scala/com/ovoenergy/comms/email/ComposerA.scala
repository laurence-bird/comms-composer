package com.ovoenergy.comms.email

import cats.Id
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.EmailSender
import com.ovoenergy.comms.templates.model.template.processed.email.EmailTemplate

sealed trait ComposerA[T]

case class RetrieveTemplate(channel: Channel, commManifest: CommManifest) extends ComposerA[EmailTemplate[Id]]

case class Render(commManifest: CommManifest,
                  template: EmailTemplate[Id],
                  data: Map[String, TemplateData],
                  customerProfile: CustomerProfile,
                  recipientEmailAddress: String)
    extends ComposerA[RenderedEmail]

case class LookupSender(template: EmailTemplate[Id], commType: CommType) extends ComposerA[EmailSender]
