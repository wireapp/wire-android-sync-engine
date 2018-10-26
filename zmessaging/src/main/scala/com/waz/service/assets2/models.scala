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

import java.net.URI

import com.waz.cache2.CacheService.Encryption
import com.waz.model._
import com.waz.sync.client.AssetClient2.Retention
import com.waz.utils.Identifiable
import org.threeten.bp.Duration

sealed trait ContentForUpload {
  def mime: Mime
  def name: String
}
object ContentForUpload {
  case class Uri(override val mime: Mime, override val name: String, uri: URI)             extends ContentForUpload
  case class Bytes(override val mime: Mime, override val name: String, bytes: Array[Byte]) extends ContentForUpload
//  case class BitmapInput(bitmap: Bitmap, orientation: Int = ExifInterface.ORIENTATION_NORMAL) extends ContentForUpload
}

case class LocalSource(uri: URI, sha: Sha256)

sealed trait RawPreview
object RawPreview {
  case object NotReady                           extends RawPreview
  case class Ready(assetId: AssetId)             extends RawPreview
  case class NotUploaded(rawAssetId: RawAssetId) extends RawPreview
  case object WithoutPreview                     extends RawPreview
}

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
    encryption: Encryption,
    localSource: Option[LocalSource],
    preview: Option[AssetId],
    details: T,
    @deprecated("This one to one relation should be removed", "")
    messageId: Option[MessageId],
    @deprecated
    convId: Option[RConvId]
) extends Identifiable[AssetId]

object Asset {
  type RawGeneral   = RawAssetDetails
  type NotReady     = DetailsNotReady.type
  type General      = AssetDetails
  type PreviewImage = PreviewImageDetails
  type Blob         = BlobDetails.type
  type Image        = ImageDetails
  type Audio        = AudioDetails
  type Video        = VideoDetails

  def create(assetId: AssetId, token: Option[AssetToken], rawAsset: RawAsset[General]): Asset[General] =
    Asset(
      id = assetId,
      token = token,
      sha = rawAsset.sha,
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
case class PreviewImageDetails(dimensions: Dim2, tag: ImageTag) extends AssetDetails

sealed trait ImageTag
case object Preview extends ImageTag
case object Medium  extends ImageTag
case object Empty   extends ImageTag

case class Loudness(levels: Vector[Float])
