package com.ovoenergy.comms.aws

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.ovoenergy.comms.templates.TemplatesContext

object TemplateContextFactory {

  def apply(runningInDockerCompose: Boolean, region: String) = {
    val awsCredentials: AWSCredentialsProvider = {
      if (runningInDockerCompose)
        new AWSStaticCredentialsProvider(new BasicAWSCredentials("service-test", "dummy"))
      else
        new AWSCredentialsProviderChain(
          new ContainerCredentialsProvider(),
          new ProfileCredentialsProvider()
        )
    }
    TemplatesContext.nonCachingContext(awsCredentials)
  }
}
