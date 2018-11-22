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
package com.waz.service.assets2

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.net.URI

import com.waz.model._
import com.waz.sync.client.AssetClient2.Retention
import com.waz.utils.Identifiable
import org.threeten.bp.Duration

import scala.util.Try

sealed trait Content {
  def openInputStream(uriHelper: UriHelper): Try[InputStream] = this match {
    case Content.Uri(uri) => uriHelper.openInputStream(uri)
    case Content.File(_, file) => Try { new FileInputStream(file) }
    case Content.Bytes(_, bytes) => Try { new ByteArrayInputStream(bytes) }
  }
}

sealed trait CanExtractMetadata extends Content

object Content {
  case class Bytes(mime: Mime, bytes: Array[Byte]) extends Content
  case class Uri(uri: URI)                         extends CanExtractMetadata
  case class File(mime: Mime, file: java.io.File)  extends CanExtractMetadata
}

/**
  * Be aware that content will be destroyed while upload process in case of [[Content.File]].
  * It means that at some point in the future [[Content.File.file]] will not exist.
  *
  * @param name name for the future asset
  * @param content content for the future asset
  */
case class ContentForUpload(name: String, content: Content)

case class LocalSource(uri: URI, sha: Sha256)

sealed trait RawPreview
case object RawPreviewNotReady                           extends RawPreview
case object RawPreviewEmpty                              extends RawPreview
case class RawPreviewNotUploaded(rawAssetId: RawAssetId) extends RawPreview
case class RawPreviewUploaded(assetId: AssetId)          extends RawPreview

case class RawAsset[+T <: RawAssetDetails](
    override val id: RawAssetId,
    localSource: Option[LocalSource],
    name: String,
    sha: Sha256,
    mime: Mime,
    preview: RawPreview,
    uploaded: Long,
    size: Long,
    retention: Retention,
    public: Boolean,
    encryption: Encryption,
    encryptionSalt: Option[Salt],
    details: T,
    uploadStatus: UploadStatus,
    assetId: Option[AssetId],
    @deprecated("This one to one relation should be removed", "")
    messageId: Option[MessageId]
) extends Identifiable[RawAssetId]

sealed trait UploadStatus
object UploadStatus {
  case object NotStarted extends UploadStatus
  case object InProgress extends UploadStatus
  case object Done       extends UploadStatus
  case object Cancelled  extends UploadStatus
  case object Failed     extends UploadStatus
}

case class Asset[+T <: AssetDetails](
    override val id: AssetId,
    token: Option[AssetToken], //all not public assets should have an AssetToken
    sha: Sha256,
    mime: Mime,
    encryption: Encryption,
    localSource: Option[LocalSource],
    preview: Option[AssetId],
    name: String,
    size: Long,
    details: T,
    @deprecated("This one to one relation should be removed", "")
    messageId: Option[MessageId],
    @deprecated
    convId: Option[RConvId]
) extends Identifiable[AssetId]

object Asset {
  type RawGeneral = RawAssetDetails
  type NotReady   = DetailsNotReady.type
  type General    = AssetDetails
  type Blob       = BlobDetails.type
  type Image      = ImageDetails
  type Audio      = AudioDetails
  type Video      = VideoDetails

  def create(assetId: AssetId, token: Option[AssetToken], rawAsset: RawAsset[General]): Asset[General] =
    Asset(
      id = assetId,
      token = token,
      mime = rawAsset.mime,
      sha = rawAsset.sha,
      name = rawAsset.name,
      size = rawAsset.size,
      encryption = rawAsset.encryption,
      localSource = rawAsset.localSource,
      preview = None,
      details = rawAsset.details,
      messageId = rawAsset.messageId,
      convId = None
    )

}

sealed trait RawAssetDetails
case object DetailsNotReady extends RawAssetDetails

sealed trait AssetDetails                                       extends RawAssetDetails
case object BlobDetails                                         extends AssetDetails
case class ImageDetails(dimensions: Dim2, tag: ImageTag)        extends AssetDetails
case class AudioDetails(duration: Duration, loudness: Loudness) extends AssetDetails
case class VideoDetails(dimensions: Dim2, duration: Duration)   extends AssetDetails

sealed trait ImageTag
case object Preview extends ImageTag
case object Medium  extends ImageTag
case object Empty   extends ImageTag

case class Loudness(levels: Vector[Float])

