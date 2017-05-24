/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.sync.client

import java.io.InputStream

import android.util.Base64
import com.koushikdutta.async.http.body.{Part, StreamPart, StringPart}
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.ErrorResponse
import com.waz.cache.{CacheEntry, Expiration, LocalData}
import com.waz.model.otr.ClientId
import com.waz.model.{Mime, _}
import com.waz.sync.client.OtrClient._
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.JsonDecoder.{apply => _, _}
import com.waz.utils.{IoUtils, JsonDecoder, JsonEncoder, LoggedTry}
import com.waz.znet.ContentEncoder._
import com.waz.znet.Response._
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet.{BinaryResponse, FileResponse, JsonObjectResponse, _}
import com.wire.messages.nano.Otr
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.util.control.NonFatal

trait AssetClient {
  import com.waz.sync.client.AssetClient._

  def loadAsset(req: Request[Unit]): ErrorOrResponse[CacheEntry]
  def postImageAssetData(asset: AssetData, data: LocalData, nativePush: Boolean = true, convId: RConvId): ErrorOrResponse[RAssetId]
  def uploadAsset(data: LocalData, mime: Mime, public: Boolean = false, retention: Retention = Retention.Persistent): ErrorOrResponse[UploadResponse]
}

class AssetClientImpl(netClient: ZNetClient) extends AssetClient {
  import Threading.Implicits.Background
  import com.waz.sync.client.AssetClient._

  override def loadAsset(req: Request[Unit]): ErrorOrResponse[CacheEntry] = {
    debug(s"AC loadAsset($req)")
    netClient.withErrorHandling("loadAsset", req) {
      case Response(SuccessHttpStatus(), resp: BinaryResponse, _) => resp
      case Response(SuccessHttpStatus(), resp: FileResponse, _) => resp
    } flatMap {
      case Right(FileResponse(file, _)) => CancellableFuture successful Right(file)
      case Right(resp) => CancellableFuture successful Left(ErrorResponse.internalError(s"unexpected response: $resp"))
      case Left(err) => CancellableFuture successful Left(err)
    }
  }

  override def postImageAssetData(asset: AssetData, data: LocalData, nativePush: Boolean = true, convId: RConvId): ErrorOrResponse[RAssetId] = {
    asset match {
      case AssetData.IsImage() =>
        val content = new MultipartRequestContent(Seq(new JsonPart(imageMetadata(asset, nativePush)), new AssetDataPart(data, asset.mime.str)), "multipart/mixed")
          netClient.withErrorHandling("postImageAssetData", Request.Post(postAssetPath(convId), content)) {
            case Response(SuccessHttpStatus(), PostImageDataResponse(rId), _) =>
              debug(s"postImageAssetData completed with resp: $rId")
              rId
          }
      case _ => CancellableFuture.failed(new Exception("Tried posting non image data"))
    }

  }

  override def uploadAsset(data: LocalData, mime: Mime, public: Boolean = false, retention: Retention = Retention.Persistent): ErrorOrResponse[UploadResponse] = {
    val meta = JsonEncoder { o =>
      o.put("public", public)
      o.put("retention", retention.value)
    }
    val content = new MultipartRequestContent(Seq(new JsonPart(meta), new AssetDataPart(data, mime.str)), "multipart/mixed")

    netClient.withErrorHandling("uploadAsset", Request.Post(AssetsV3Path, content)) {
      case Response(SuccessHttpStatus(), UploadResponseExtractor(resp), _) =>
        debug(s"uploadAsset completed with resp: $resp")
        resp
    }
  }
}

object AssetClient {

  implicit val DefaultExpiryTime: Expiration = 1.hour

  def apply(netClient: ZNetClient): AssetClient = new AssetClientImpl(netClient)


  val AssetsV3Path = "/assets/v3"

  sealed abstract class Retention(val value: String)
  object Retention {
    case object Eternal extends Retention("eternal")
    case object Persistent extends Retention("persistent")
    case object Volatile extends Retention("volatile")
  }

  case class UploadResponse(rId: RAssetId, expires: Option[Instant], token: Option[AssetToken])

  case object UploadResponse {

    implicit object Decoder extends JsonDecoder[UploadResponse] {
      import JsonDecoder._
      override def apply(implicit js: JSONObject): UploadResponse = UploadResponse(RAssetId('key), decodeOptISOInstant('expires), decodeOptString('token).map(AssetToken))
    }
  }

  object UploadResponseExtractor {
    def unapply(content: JsonObjectResponse): Option[UploadResponse] = LoggedTry.local(UploadResponse.Decoder(content.value)).toOption
  }

  def postAssetPath(conv: RConvId) = s"/conversations/$conv/assets"

  //TODO remove asset v2 when transition period is over
  def getAssetPath(remoteId: Option[RAssetId], otrKey: Option[AESKey], conv: Option[RConvId]): Option[String] = remoteId.map { rId =>
    (conv, otrKey) match {
      case (None, _)          => s"/assets/v3/${rId.str}"
      case (Some(c), None)    => s"/conversations/${c.str}/assets/${rId.str}"
      case (Some(c), Some(_)) => s"/conversations/${c.str}/otr/assets/${rId.str}"
    }
  }

  def imageMetadata(asset: AssetData, nativePush: Boolean) = JsonEncoder { o =>
    asset match {
      case a@AssetData.IsImage() =>
        o.put("width", a.width)
        o.put("height", a.height)
        o.put("original_width", a.width)
        o.put("original_height", a.height)
        o.put("inline", asset.data.fold(false)(_.length < 10000))
        o.put("public", true)
        o.put("tag", a.tag)
        o.put("correlation_id", asset.id)
        o.put("nonce", asset.id)
        o.put("native_push", nativePush)
      case _ => new JSONObject()
    }

  }

  case class OtrAssetMetadata(sender: ClientId, recipients: EncryptedContent, nativePush: Boolean = true, inline: Boolean = false)

  object OtrAssetMetadata {

    implicit lazy val OtrMetaEncoder: ContentEncoder[OtrAssetMetadata] = new ContentEncoder[OtrAssetMetadata] {
      override def apply(meta: OtrAssetMetadata): RequestContent = {
        val data = new Otr.OtrAssetMeta
        data.sender = OtrClient.clientId(meta.sender)
        data.recipients = meta.recipients.userEntries
        data.isInline = meta.inline
        data.nativePush = meta.nativePush

        ContentEncoder.protobuf(data)
      }
    }
  }

  case class OtrAssetResponse(assetId: RAssetId, result: MessageResponse)

  object PostImageDataResponse {
    def decodeAssetAddEvent(implicit js: JSONObject) = decodeRAssetId('id)(js.getJSONObject("data"))

    def unapply(response: ResponseContent): Option[RAssetId] = try {
      response match {
        case JsonObjectResponse(js) =>
          js.optString("type", "") match {
            case "conversation.asset-add" => LoggedTry(decodeAssetAddEvent(js)).toOption
            case _ =>
              warn(s"unexpected event received when waiting for image asset add: $js")
              None
          }
        case _ => None
      }
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse image add event from response: $response", e)
        None
    }
  }
}

class JsonPart(json: JSONObject) extends StringPart("", json.toString) {
  setContentType("application/json")
  getRawHeaders.set("Content-Length", length().toString)
  getRawHeaders.remove(Part.CONTENT_DISPOSITION)

  override def toString: LogTag = s"JsonPart($json)"
}

class LocalDataPart(data: LocalData, contentType: String) extends StreamPart("", data.length, null) {
  setContentType(contentType)
  getRawHeaders.set("Content-Length", length().toString)
  getRawHeaders.remove(Part.CONTENT_DISPOSITION)

  override def getInputStream: InputStream = data.inputStream

  override def toString: LogTag = s"LocalDataPart($data, $contentType)"
}

class AssetDataPart(data: LocalData, contentType: String) extends LocalDataPart(data, contentType) {
  getRawHeaders.add("Content-MD5", AssetDataPart.md5(data))

  override def toString: LogTag = s"AssetDataPart($data, $contentType)"
}

object AssetDataPart {
  /**
   * Computes base64 encoded md5 sum of image data.
   */
  def md5(data: LocalData): String = Base64.encodeToString(IoUtils.md5(data.inputStream), Base64.NO_WRAP)
}
