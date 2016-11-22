package com.ovoenergy.comms.email

import com.ovoenergy.comms._

sealed trait ComposerA[T]

case class RetrieveTemplate(channel: Channel, commManifest: CommManifest) extends ComposerA[EmailTemplate]

case class Render(commManifest: CommManifest,
                  template: EmailTemplate,
                  data: Map[String, String],
                  customerProfile: CustomerProfile,
                  recipientEmailAddress: String)
    extends ComposerA[RenderedEmail]

case class LookupSender(template: EmailTemplate, commType: CommType) extends ComposerA[EmailSender]
