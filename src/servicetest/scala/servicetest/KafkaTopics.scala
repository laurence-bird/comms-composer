package servicetest

// TODO: is this trait used anywhere?
trait KafkaTopics {

  val orchestratedEmailTopic = "comms.orchestrated.email.v3"
  val orchestratedSMSTopic = "comms.orchestrated.sms.v2"

  val composedEmailTopic = "comms.composed.email.v2"
  val composedSMSTopic = "comms.composed.sms.v2"
  val failedTopic = "comms.failed.v2"

  val aivenTopics = Seq(
    orchestratedEmailTopic,
    orchestratedSMSTopic,
    composedEmailTopic,
    composedSMSTopic,
    failedTopic
  )

}