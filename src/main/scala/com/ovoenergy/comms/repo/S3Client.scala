package com.ovoenergy.comms.repo

import java.nio.charset.StandardCharsets

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.util.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

trait S3Client {

  def getUTF8TextFileContent(key: String): Option[String]

  def listFiles(prefix: String): Seq[String]

}

class AmazonS3ClientWrapper(client: AmazonS3Client) extends S3Client {

  private val log = LoggerFactory.getLogger(getClass)

  private val TemplatesBucket = "ovo-comms-templates"

  override def getUTF8TextFileContent(key: String): Option[String] = {
    try {
      Option(client.getObject(TemplatesBucket, key)).map(obj => {
        val stream = obj.getObjectContent
        try {
          new String(IOUtils.toByteArray(stream), StandardCharsets.UTF_8)
        } finally {
          stream.close()
        }
      })
    } catch {
      case e: AmazonS3Exception =>
        // either the object does not exist or something went really wrong
        if (e.getStatusCode != 404)
          log.warn(s"Failed to download s3://$TemplatesBucket/$key", e)
        None
    }
  }

  override def listFiles(prefix: String): Seq[String] = {
    try {
      client.listObjects(TemplatesBucket, prefix).getObjectSummaries.asScala.map(_.getKey)
    } catch {
      case e: AmazonS3Exception =>
        log.warn(s"Failed to list objects under s3://$TemplatesBucket/$prefix", e)
        Nil
    }
  }

}
