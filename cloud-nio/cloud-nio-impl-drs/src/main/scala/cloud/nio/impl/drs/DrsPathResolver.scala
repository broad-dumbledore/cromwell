package cloud.nio.impl.drs

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.implicits._
import cloud.nio.impl.drs.MarthaResponseSupport._
import common.exception.toIO
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import mouse.boolean._
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.{ContentType, StringEntity}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import org.apache.http.{HttpResponse, HttpStatus, StatusLine}

import java.nio.ByteBuffer
import java.nio.channels.{Channels, ReadableByteChannel}
import scala.util.Try

abstract class DrsPathResolver(drsConfig: DrsConfig, retryInternally: Boolean = true) {

  protected lazy val httpClientBuilder: HttpClientBuilder = {
    val clientBuilder = HttpClientBuilder.create()
    if (retryInternally) {
      val retryHandler = new MarthaHttpRequestRetryStrategy(drsConfig)
      clientBuilder
        .setRetryHandler(retryHandler)
        .setServiceUnavailableRetryStrategy(retryHandler)
    }
    clientBuilder
  }

  def getAccessToken: String

  private def makeHttpRequestToMartha(drsPath: String, fields: NonEmptyList[MarthaField.Value]): HttpPost = {
    val postRequest = new HttpPost(drsConfig.marthaUrl)
    val requestJson = MarthaRequest(drsPath, fields).asJson.noSpaces
    postRequest.setEntity(new StringEntity(requestJson, ContentType.APPLICATION_JSON))
    postRequest.setHeader("Authorization", s"Bearer $getAccessToken")
    postRequest
  }

  private def httpResponseToMarthaResponse(drsPathForDebugging: String)(httpResponse: HttpResponse): IO[MarthaResponse] = {
    val marthaResponseEntityOption = Option(httpResponse.getEntity).map(EntityUtils.toString)
    val responseStatusLine = httpResponse.getStatusLine

    val exceptionMsg = errorMessageFromResponse(drsPathForDebugging, marthaResponseEntityOption, responseStatusLine, drsConfig.marthaUrl)
    val responseEntityOption = (responseStatusLine.getStatusCode == HttpStatus.SC_OK).valueOrZero(marthaResponseEntityOption)
    val responseContentIO = toIO(responseEntityOption, exceptionMsg)

    responseContentIO.flatMap{ responseContent =>
      IO.fromEither(decode[MarthaResponse](responseContent))
    }.handleErrorWith {
      e => IO.raiseError(new RuntimeException(s"Unexpected response during DRS resolution: ${ExceptionUtils.getMessage(e)}"))
    }
  }

  private def executeMarthaRequest(httpPost: HttpPost): Resource[IO, HttpResponse]= {
    for {
      httpClient <- Resource.fromAutoCloseable(IO(httpClientBuilder.build()))
      httpResponse <- Resource.fromAutoCloseable(IO(httpClient.execute(httpPost)))
    } yield httpResponse
  }

  def rawMarthaResponse(drsPath: String, fields: NonEmptyList[MarthaField.Value]): Resource[IO, HttpResponse] = {
    val httpPost = makeHttpRequestToMartha(drsPath, fields)
    executeMarthaRequest(httpPost)
  }

  /** *
    * Resolves the DRS path through Martha url provided in the config.
    * Please note, this method returns an IO that would make a synchronous HTTP request to Martha when run.
    */
  def resolveDrsThroughMartha(drsPath: String, fields: NonEmptyList[MarthaField.Value]): IO[MarthaResponse] = {
    rawMarthaResponse(drsPath, fields).use(httpResponseToMarthaResponse(drsPathForDebugging = drsPath))
  }

  def openChannel(accessUrl: AccessUrl): IO[ReadableByteChannel] = {
    IO {
      val httpGet = new HttpGet(accessUrl.url)
      accessUrl.headers.getOrElse(Map.empty).toList foreach {
        case (name, value) => httpGet.addHeader(name, value)
      }
      val client = httpClientBuilder.build()
      val response = client.execute(httpGet)
      val inner = Channels.newChannel(response.getEntity.getContent)
      /*
      Create a wrapper ReadableByteChannel. When .close() is invoked on the wrapper, the wrapper will internally call
      .close() on:
      - the inner ReadableByteChannel
      - the HttpResponse
      - the HttpClient

      This ensures that when the channel is released any underlying HTTP connections are also released.
       */
      new ReadableByteChannel {
        override def read(dst: ByteBuffer): Int = inner.read(dst)

        override def isOpen: Boolean = inner.isOpen

        //noinspection ScalaUnusedExpression
        override def close(): Unit = {
          val innerTry = Try(inner.close())
          val responseTry = Try(response.close())
          val clientTry = Try(client.close())
          innerTry.get
          responseTry.get
          clientTry.get
        }
      }
    }
  }
}

object DrsPathResolver {
  final val ExtractUriErrorMsg = "No access URL nor GCS URI starting with 'gs://' found in Martha response!"
}

object MarthaField extends Enumeration {
  val GsUri: MarthaField.Value = Value("gsUri")
  val Size: MarthaField.Value = Value("size")
  val TimeCreated: MarthaField.Value = Value("timeCreated")
  val TimeUpdated: MarthaField.Value = Value("timeUpdated")
  val BondProvider: MarthaField.Value = Value("bondProvider")
  val GoogleServiceAccount: MarthaField.Value = Value("googleServiceAccount")
  val Hashes: MarthaField.Value = Value("hashes")
  val FileName: MarthaField.Value = Value("fileName")
  val AccessUrl: MarthaField.Value = Value("accessUrl")
}

final case class MarthaRequest(url: String, fields: NonEmptyList[MarthaField.Value])

final case class SADataObject(data: Json)

final case class AccessUrl(url: String, headers: Option[Map[String, String]])

/**
  * A response from `martha_v3`.
  *
  * @param size Size of the object stored at gsUri
  * @param timeCreated The creation time of the object at gsUri
  * @param timeUpdated The last update time of the object at gsUri
  * @param gsUri Where the object bytes are stored, possibly using a generated path name such as "gs://bucket/12/345"
  * @param bondProvider The bond provider returning the googleServiceAccount
  * @param googleServiceAccount The service account to access the gsUri contents created via bondProvider
  * @param fileName A possible different file name for the object at gsUri, ex: "gsutil cp gs://bucket/12/345 my.vcf"
  * @param hashes Hashes for the contents stored at gsUri
  * @param accessUrl URL to query for signed URL
  */
final case class MarthaResponse(size: Option[Long] = None,
                                timeCreated: Option[String] = None,
                                timeUpdated: Option[String] = None,
                                gsUri: Option[String] = None,
                                bondProvider: Option[String] = None,
                                googleServiceAccount: Option[SADataObject] = None,
                                fileName: Option[String] = None,
                                hashes: Option[Map[String, String]] = None,
                                accessUrl: Option[AccessUrl] = None
                               )

// Adapted from https://github.com/broadinstitute/martha/blob/f31933a3a11e20d30698ec4b4dc1e0abbb31a8bc/common/helpers.js#L210-L218
final case class MarthaFailureResponse(response: MarthaFailureResponsePayload)
final case class MarthaFailureResponsePayload(text: String)

object MarthaResponseSupport {

  implicit lazy val marthaFieldEncoder: Encoder[MarthaField.Value] = Encoder.encodeEnumeration(MarthaField)
  implicit lazy val marthaRequestEncoder: Encoder[MarthaRequest] = deriveEncoder

  implicit lazy val saDataObjectDecoder: Decoder[SADataObject] = deriveDecoder
  implicit lazy val marthaResponseDecoder: Decoder[MarthaResponse] = deriveDecoder

  implicit lazy val marthaFailureResponseDecoder: Decoder[MarthaFailureResponse] = deriveDecoder
  implicit lazy val marthaFailureResponsePayloadDecoder: Decoder[MarthaFailureResponsePayload] = deriveDecoder

  implicit lazy val marthaAccessUrlDecoder: Decoder[AccessUrl] = deriveDecoder

  private val GcsScheme = "gs://"

  def getGcsBucketAndName(gcsUrl: String): (String, String) = {
     val array = gcsUrl.substring(GcsScheme.length).split("/", 2)
      (array(0), array(1))
  }

  def errorMessageFromResponse(drsPathForDebugging: String, marthaResponseEntityOption: Option[String], responseStatusLine: StatusLine, marthaUri: String): String = {
    val baseMessage = s"Could not access object \'$drsPathForDebugging\'. Status: ${responseStatusLine.getStatusCode}, reason: \'${responseStatusLine.getReasonPhrase}\', Martha location: \'$marthaUri\', message: "

    marthaResponseEntityOption match {
      case Some(entity) =>
        val maybeErrorResponse: Either[Error, MarthaFailureResponse] = decode[MarthaFailureResponse](entity)
        maybeErrorResponse match {
          case Left(_) =>
            // Not parsable as a `MarthaFailureResponse`
            baseMessage + s"\'$entity\'"
          case Right(decoded) =>
            // Is a `MarthaFailureResponse`
            baseMessage + s"\'${decoded.response.text}\'"
        }
      case None =>
        // No entity in HTTP response
        baseMessage + "(empty response)"
    }
  }
}
