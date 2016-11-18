package com.ovoenergy.comms.email

import com.ovoenergy.comms.Mustache

case class EmailTemplate(subject: Mustache,
                         htmlBody: Mustache,
                         textBody: Option[Mustache],
                         sender: Option[String],
                         htmlFragments: Map[String, Mustache],
                         textFragments: Map[String, Mustache])
