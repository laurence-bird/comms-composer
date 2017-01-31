package com.ovoenergy.comms.aws

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.comms.repo.AmazonS3ClientWrapper

object S3ClientFactory {

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
    val underlying: AmazonS3Client =
      new AmazonS3Client(awsCredentials).withRegion(Regions.fromName(region))
    new AmazonS3ClientWrapper(underlying)
  }
}
