package com.ovoenergy.comms.composer
package email

import com.ovoenergy.comms.composer.v2.model.Fragment

case class RenderedEmail(subject: Fragment, htmlBody: Fragment, textBody: Option[Fragment])
