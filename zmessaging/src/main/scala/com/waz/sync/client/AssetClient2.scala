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

import java.io.{BufferedOutputStream, File, FileOutputStream, InputStream}
import java.security.{DigestOutputStream, MessageDigest}

import com.waz.api.impl.ErrorResponse
import com.waz.cache.{Expiration, LocalData}
import com.waz.cache2.CacheService.NoEncryption
import com.waz.model._
import com.waz.service.assets2.Asset
import com.waz.service.assets2.Asset.General
import com.waz.utils.crypto.AESUtils
import com.waz.utils.{CirceJSONSupport, IoUtils}
import com.waz.znet2.http.HttpClient.AutoDerivation._
import com.waz.znet2.http.HttpClient.ProgressCallback
import com.waz.znet2.http.HttpClient.dsl._
import com.waz.znet2.http.MultipartBodyMixed.Part
import com.waz.znet2.http.Request.UrlCreator
import com.waz.znet2.http._
import io.circe.Encoder
import org.threeten.bp.Instant

import scala.concurrent.duration._

trait AssetClient2 {
  import com.waz.sync.client.AssetClient2._

  def loadAssetContent(asset: Asset[General], callback: Option[ProgressCallback]): ErrorOrResponse[FileWithSha]
  def uploadAsset(metadata: Metadata, asset: AssetContent, callback: Option[ProgressCallback]): ErrorOrResponse[UploadResponse2]
  def deleteAsset(assetId: AssetId): ErrorOrResponse[Boolean]
}

class AssetClient2Impl(implicit
                       urlCreator: UrlCreator,
                       client: HttpClient,
                       authRequestInterceptor: RequestInterceptor = RequestInterceptor.identity)
  extends AssetClient2 with CirceJSONSupport {

  import AssetClient2._
  import com.waz.threading.Threading.Implicits.Background

  private implicit def fileWithShaBodyDeserializer: RawBodyDeserializer[FileWithSha] =
    RawBodyDeserializer.create { body =>
      val tempFile = File.createTempFile("http_client_download", null)
      val out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)),
        MessageDigest.getInstance("SHA-256"))
      IoUtils.copy(body.data(), out)
      FileWithSha(tempFile, Sha256(out.getMessageDigest.digest()))
    }

  override def loadAssetContent(asset: Asset[General], callback: Option[ProgressCallback]): ErrorOrResponse[FileWithSha] = {
    val assetPath = (asset.convId, asset.encryption) match {
      case (None, _)                     => s"/assets/v3/${asset.id.str}"
      case (Some(convId), NoEncryption)  => s"/conversations/${convId.str}/assets/${asset.id.str}"
      case (Some(convId), _)             => s"/conversations/${convId.str}/otr/assets/${asset.id.str}"
    }

    Request
      .Get(
        relativePath = assetPath,
        headers = asset.token.fold(Headers.empty)(token => Headers("Asset-Token" -> token.str))
      )
      .withDownloadCallback(callback)
      .withResultType[FileWithSha]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  private implicit def RawAssetRawBodySerializer: RawBodySerializer[AssetContent] =
    RawBodySerializer.create { asset =>
      RawBody(mediaType = Some(asset.mime.str), asset.data, dataLength = asset.dataLength)
    }

  override def uploadAsset(metadata: Metadata, content: AssetContent, callback: Option[ProgressCallback]): ErrorOrResponse[UploadResponse2] = {
    Request
      .Post(
        relativePath = AssetsV3Path,
        body = MultipartBodyMixed(Part(metadata), Part(content, Headers("Content-MD5" -> md5(content.data()))))
      )
      .withUploadCallback(callback)
      .withResultType[UploadResponse2]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def deleteAsset(assetId: AssetId): ErrorOrResponse[Boolean] = {
    Request.Delete(relativePath = s"$AssetsV3Path/${assetId.str}")
      .withResultHttpCodes(ResponseCode.SuccessCodes + ResponseCode.NotFound)
      .withResultType[Response[Unit]]
      .withErrorType[ErrorResponse]
      .executeSafe(_.code != ResponseCode.NotFound)
  }
}

object AssetClient2 {

  case class FileWithSha(file: File, sha256: Sha256)

  case class AssetContent(mime: Mime, data: () => InputStream, dataLength: Option[Long])

  case class UploadResponse2(key: AssetId, expires: Option[Instant], token: Option[AssetToken])

  implicit val DefaultExpiryTime: Expiration = 1.hour

  val AssetsV3Path = "/assets/v3"

  sealed trait Retention
  object Retention {
    case object Eternal                 extends Retention //Only used for profile pics currently
    case object EternalInfrequentAccess extends Retention
    case object Persistent              extends Retention
    case object Expiring                extends Retention
    case object Volatile                extends Retention
  }

  implicit def retentionEncoder: Encoder[Retention] = Encoder[String].contramap {
    case Retention.Eternal => "eternal"
    case Retention.EternalInfrequentAccess => "eternal-infrequent_access"
    case Retention.Persistent => "persistent"
    case Retention.Expiring => "expiring"
    case Retention.Volatile => "volatile"
  }

  case class Metadata(public: Boolean = false, retention: Retention = Retention.Persistent)

  def getAssetPath(rId: RAssetId, otrKey: Option[AESKey], conv: Option[RConvId]): String =
    (conv, otrKey) match {
      case (None, _)          => s"/assets/v3/${rId.str}"
      case (Some(c), None)    => s"/conversations/${c.str}/assets/${rId.str}"
      case (Some(c), Some(_)) => s"/conversations/${c.str}/otr/assets/${rId.str}"
    }

  /**
    * Computes base64 encoded md5 sum of image data.
    */
  def md5(data: LocalData): String = md5(data.inputStream)

  def md5(is: InputStream): String = AESUtils.base64(IoUtils.md5(is))

}
