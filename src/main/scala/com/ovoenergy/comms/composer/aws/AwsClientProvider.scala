package com.ovoenergy.comms.composer.aws

import com.amazonaws.auth.{
  AWSCredentialsProviderChain,
  AWSStaticCredentialsProvider,
  BasicAWSCredentials,
  ContainerCredentialsProvider
}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client

object AwsClientProvider {
  def genClients(runningInDockerOnDevMachine: Boolean, region: Regions): AmazonS3Client = {
    if (runningInDockerOnDevMachine) {
      System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
      val awsCreds = new AWSStaticCredentialsProvider(new BasicAWSCredentials("key", "secret"))
      new AmazonS3Client(awsCreds).withRegion(region)
    } else {
      val awsCreds = new AWSCredentialsProviderChain(
        new ContainerCredentialsProvider(),
        new ProfileCredentialsProvider("default")
      )
      new AmazonS3Client(awsCreds).withRegion(region)
    }
  }
}
