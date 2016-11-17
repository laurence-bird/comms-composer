package com.ovoenergy.comms

case class Mustache(content: String)

case class Template(sender: Option[String],
                    subject: Mustache,
                    htmlBody: Mustache,
                    textBody: Option[Mustache],
                    htmlFragments: Map[String, Mustache],
                    textFragments: Map[String, Mustache])
