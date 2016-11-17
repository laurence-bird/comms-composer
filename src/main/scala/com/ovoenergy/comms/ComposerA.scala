package com.ovoenergy.comms

sealed trait ComposerA[T]

case class RetrieveTemplate(channel: Channel, commManifest: CommManifest) extends ComposerA[Template]

case class Render(commManifest: CommManifest,
                  template: Template,
                  data: Map[String, String],
                  customerProfile: CustomerProfile)
    extends ComposerA[RenderedEmail]

case class LookupSender(template: Template, commType: CommType) extends ComposerA[Sender]
