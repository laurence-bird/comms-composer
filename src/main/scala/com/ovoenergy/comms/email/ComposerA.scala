package com.ovoenergy.comms.email

import com.ovoenergy.comms.model._

sealed trait ComposerA[T]

case class RetrieveTemplate(channel: Channel, commManifest: CommManifest) extends ComposerA[EmailTemplate]

case class Render(commManifest: CommManifest,
                  template: EmailTemplate,
                  data: Map[String, TemplateData],
                  customerProfile: CustomerProfile,
                  recipientEmailAddress: String)
    extends ComposerA[RenderedEmail]

case class LookupSender(template: EmailTemplate, commType: CommType) extends ComposerA[EmailSender]
