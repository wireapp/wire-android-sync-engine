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

import com.waz.model.GenericContent.Asset.{Original, Preview}
import com.waz.model._
import com.waz.model.GenericContent.{Asset => GenericAsset}
import com.waz.model.nano.Messages
import com.waz.model.nano.Messages.Asset.RemoteData
import com.waz.sync.client.AssetClient2.Retention
import com.waz.utils.Identifiable
import org.threeten.bp.Duration

import scala.util.{Success, Try}

sealed trait Content {

  def openInputStream(uriHelper: UriHelper): Try[InputStream] = this match {
    case Content.Bytes(_, bytes) => Try { new ByteArrayInputStream(bytes) }
    case Content.Uri(uri)        => uriHelper.openInputStream(uri)
    case Content.File(_, file)   => Try { new FileInputStream(file) }
    case Content.AsBlob(content) => content.openInputStream(uriHelper)
  }

  def getSize(uriHelper: UriHelper): Try[Long] = this match {
    case Content.Bytes(_, bytes) => Success(bytes.length)
    case Content.Uri(uri)        => uriHelper.extractSize(uri)
    case Content.File(_, file)   => Try { file.length() }
    case Content.AsBlob(content) => content.getSize(uriHelper)
  }

  def getMime(uriHelper: UriHelper): Try[Mime] = this match {
    case Content.Bytes(mime, _)  => Success(mime)
    case Content.Uri(uri)        => uriHelper.extractMime(uri)
    case Content.File(mime, _)   => Success(mime)
    case Content.AsBlob(content) => content.getMime(uriHelper)
  }

}

//TODO Maybe rename this to 'PreparedContent' ?
sealed trait CanExtractMetadata extends Content

object Content {
  case class Bytes(mime: Mime, bytes: Array[Byte]) extends Content
  case class AsBlob(content: Content)              extends Content
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
case object RawPreviewNotReady                              extends RawPreview
case object RawPreviewEmpty                                 extends RawPreview
case class RawPreviewNotUploaded(rawAssetId: UploadAssetId) extends RawPreview
case class RawPreviewUploaded(assetId: AssetId)             extends RawPreview

sealed trait GeneralAsset {
  def id: AssetIdGeneral
  def mime: Mime
  def name: String
  def size: Long
  def details: UploadAssetDetails
}

case class UploadAsset[+T <: UploadAssetDetails](
    id: UploadAssetId,
    localSource: Option[LocalSource],
    name: String,
    sha: Sha256,
    md5: MD5,
    mime: Mime,
    preview: RawPreview,
    uploaded: Long,
    size: Long,
    retention: Retention,
    public: Boolean,
    encryption: Encryption,
    encryptionSalt: Option[Salt],
    details: T,
    status: UploadAssetStatus,
    assetId: Option[AssetId]
) extends GeneralAsset
    with Identifiable[UploadAssetId]

sealed trait AssetStatus
object AssetStatus {
  case object Done extends UploadAssetStatus with DownloadAssetStatus
}

sealed trait UploadAssetStatus extends AssetStatus
object UploadAssetStatus {
  case object NotStarted extends UploadAssetStatus
  case object InProgress extends UploadAssetStatus
  case object Cancelled  extends UploadAssetStatus
  case object Failed     extends UploadAssetStatus
}

sealed trait DownloadAssetStatus extends AssetStatus
object DownloadAssetStatus {
  case object NotStarted extends DownloadAssetStatus
  case object InProgress extends DownloadAssetStatus
  case object Cancelled  extends DownloadAssetStatus
  case object Failed     extends DownloadAssetStatus
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
    @deprecated
    convId: Option[RConvId]
) extends GeneralAsset
    with Identifiable[AssetId]

case class DownloadAsset(
    id: DownloadAssetId,
    mime: Mime,
    name: String,
    preview: Option[AssetId],
    details: AssetDetails,
    downloaded: Long,
    size: Long,
    status: DownloadAssetStatus
) extends GeneralAsset
    with Identifiable[DownloadAssetId]

object DownloadAsset {

  def create(asset: GenericAsset): DownloadAsset = {
    val original = Option(asset.original)

    val (mime, size, detailsInput) = original match {
      case Some(o) => (o.mimeType, o.size, Left(o))
      case _ => (asset.preview.mimeType, asset.preview.size, Right(asset.preview))
    }

    DownloadAsset(
      id = DownloadAssetId(),
      mime = Mime(mime),
      name = original.map(_.name).getOrElse(""),
      preview = Option(asset.preview).flatMap(p => Option(p.remote)).map(r=> AssetId(r.assetId)),
      details = Asset.extractDetails(detailsInput),
      downloaded = 0,
      size = size,
      status = getStatus(asset)
    )
  }

  def getStatus(proto: GenericAsset): DownloadAssetStatus = {
    proto.getStatusCase match {
      case Messages.Asset.UPLOADED_FIELD_NUMBER => AssetStatus.Done
      case Messages.Asset.NOT_UPLOADED_FIELD_NUMBER =>
        proto.getNotUploaded match {
          case Messages.Asset.CANCELLED => DownloadAssetStatus.Cancelled
          case Messages.Asset.FAILED => DownloadAssetStatus.Failed
          case _ => DownloadAssetStatus.InProgress
        }
      case _ => DownloadAssetStatus.InProgress
    }
  }

}

object Asset {
  type UploadGeneral = UploadAssetDetails
  type NotReady      = DetailsNotReady.type
  type General       = AssetDetails
  type Blob          = BlobDetails.type
  type Image         = ImageDetails
  type Audio         = AudioDetails
  type Video         = VideoDetails

  def extractEncryption(remote: RemoteData): Encryption = remote.encryption match {
    case Messages.AES_GCM => AES_CBC_Encryption(AESKey2(remote.otrKey))
    case Messages.AES_CBC => AES_CBC_Encryption(AESKey2(remote.otrKey))
    case _                => NoEncryption
  }

  def extractDetails(either: Either[Original, Preview]): AssetDetails =
    if (either.fold(_.hasImage, _.hasImage)) {
      val image = either.fold(_.getImage, _.getImage)
      ImageDetails(Dim2(image.width, image.height))
    } else
      either match {
        case Left(original) if original.hasAudio =>
          val audio = original.getAudio
          AudioDetails(Duration.ofMillis(audio.durationInMillis), Loudness(audio.normalizedLoudness.toVector))
        case Left(original) if original.hasVideo =>
          val video = original.getVideo
          VideoDetails(Dim2(video.width, video.height), Duration.ofMillis(video.durationInMillis))
        case _ =>
          BlobDetails
      }

  def create(asset: DownloadAsset, remote: RemoteData): Asset[General] =
    Asset(
      id = AssetId(remote.assetId),
      token = if (remote.assetToken.isEmpty) None else Some(AssetToken(remote.assetToken)),
      sha = Sha256(remote.sha256),
      mime = asset.mime,
      encryption = extractEncryption(remote),
      localSource = None,
      preview = asset.preview,
      name = asset.name,
      size = asset.size,
      details = asset.details,
      convId = None
    )

  def create(preview: Preview): Asset[General] = {
    val remote = preview.remote
    Asset(
      id = AssetId(remote.assetId),
      token = if (remote.assetToken.isEmpty) None else Some(AssetToken(remote.assetToken)),
      sha = Sha256(remote.sha256),
      mime = Mime(preview.mimeType),
      encryption = extractEncryption(remote),
      localSource = None,
      preview = None,
      name = s"preview_${System.currentTimeMillis()}",
      size = preview.size,
      details = Asset.extractDetails(Right(preview)),
      convId = None
    )
  }

  def create(assetId: AssetId, token: Option[AssetToken], uploadAsset: UploadAsset[General]): Asset[General] =
    Asset(
      id = assetId,
      token = token,
      mime = uploadAsset.mime,
      sha = uploadAsset.sha,
      name = uploadAsset.name,
      size = uploadAsset.size,
      encryption = uploadAsset.encryption,
      localSource = uploadAsset.localSource,
      preview = uploadAsset.preview match {
        case RawPreviewUploaded(aId) => Some(assetId)
        case _ => None
      },
      details = uploadAsset.details,
      convId = None
    )

}

sealed trait UploadAssetDetails
case object DetailsNotReady extends UploadAssetDetails

sealed trait AssetDetails                                       extends UploadAssetDetails
case object BlobDetails                                         extends AssetDetails
case class ImageDetails(dimensions: Dim2)                       extends AssetDetails
case class AudioDetails(duration: Duration, loudness: Loudness) extends AssetDetails
case class VideoDetails(dimensions: Dim2, duration: Duration)   extends AssetDetails

sealed trait ImageTag
case object Preview extends ImageTag
case object Medium  extends ImageTag
case object Empty   extends ImageTag

case class Loudness(levels: Vector[Byte])
