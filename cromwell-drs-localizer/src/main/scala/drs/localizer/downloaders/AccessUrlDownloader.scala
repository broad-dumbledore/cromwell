package drs.localizer.downloaders

import cats.effect.{ExitCode, IO}
import cloud.nio.impl.drs.AccessUrl
import com.typesafe.scalalogging.StrictLogging

import scala.sys.process.{Process, ProcessLogger}

case class AccessUrlDownloader(accessUrl: AccessUrl, downloadLoc: String) extends Downloader with StrictLogging {

  def generateDownloadScript(): String = {
    val signedUrl = accessUrl.url
    // TODO headers, refinements to retry strategy for certain 4xx statuses
    s"""mkdir -p $$(dirname '$downloadLoc') && curl --location --retry 3 --retry-connrefused --retry-delay 10 --fail --output '$downloadLoc' '$signedUrl'"""
  }

  override def download: IO[ExitCode] = {
    val signedUrl = accessUrl.url
      // TODO probably don't want to log the actual signed URL
      logger.info(s"Attempting to download $signedUrl to $downloadLoc")
      val copyCommand = Seq("bash", "-c", generateDownloadScript())
      val copyProcess = Process(copyCommand)

      val returnCode = copyProcess ! ProcessLogger(logger.underlying.info, logger.underlying.error)

      IO(ExitCode(returnCode))
  }
}
