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

import android.util.Base64
import com.waz.model.{MessageId, Mime, RawAssetId}
import com.waz.sync.client.AssetClient2.Retention

trait Codec[From, To] {
  def serialize(value: From): To
  def deserialize(value: To): From
}

object Codec {
  def create[From, To](to: From => To, from: To => From): Codec[From, To] =
    new Codec[From, To] {
      override def serialize(value: From): To   = to(value)
      override def deserialize(value: To): From = from(value)
    }
}

trait StorageCodecs {
  import java.net.URI

  import com.waz.model.{AssetId, AssetToken, Sha256}
  import io.circe.parser._
  import io.circe.syntax._
  import io.circe.{Decoder, Encoder}

  def asBase64String(bytes: Array[Byte]): String = Base64.encodeToString(bytes, Base64.NO_PADDING)
  def asBytes(base64String: String): Array[Byte] = Base64.decode(base64String, Base64.NO_PADDING)

  implicit val SaltCodec: Codec[Salt, String] = new Codec[Salt, String] {
    override def serialize(value: Salt): String = asBase64String(value.bytes)
    override def deserialize(value: String): Salt = Salt(asBytes(value))
  }

  implicit val EncryptionCodec: Codec[Encryption, String] = new Codec[Encryption, String] {
    val Unencrypted    = ""
    val AES_CBC_Prefix = "AES_CBS__"

    override def serialize(value: Encryption): String = value match {
      case NoEncryption            => Unencrypted
      case AES_CBC_Encryption(key) => AES_CBC_Prefix + asBase64String(key.bytes)
    }

    override def deserialize(value: String): Encryption = value match {
      case Unencrypted => NoEncryption
      case str if str.startsWith(AES_CBC_Prefix) =>
        AES_CBC_Encryption(AESKey2(asBytes(str.substring(AES_CBC_Prefix.length))))
    }
  }

  implicit val RawPreviewCodec: Codec[RawPreview, String] = new Codec[RawPreview, String] {
    val NotReady = "not_ready"
    val WithoutPreview = ""
    val NotUploadedPrefix = "not_uploaded__"
    val UploadedPrefix = "uploaded__"

    override def serialize(value: RawPreview): String = value match {
      case RawPreviewNotReady => NotReady
      case RawPreviewEmpty => WithoutPreview
      case RawPreviewNotUploaded(rawAssetId) => NotUploadedPrefix + rawAssetId.str
      case RawPreviewUploaded(assetId) => UploadedPrefix + assetId.str
    }

    override def deserialize(value: String): RawPreview = value match {
      case NotReady => RawPreviewNotReady
      case WithoutPreview => RawPreviewEmpty
      case str if str.startsWith(NotUploadedPrefix) =>
        RawPreviewNotUploaded(RawAssetId(str.substring(NotUploadedPrefix.length)))
      case str if str.startsWith(UploadedPrefix) =>
        RawPreviewUploaded(AssetId(str.substring(UploadedPrefix.length)))
    }
  }

  implicit val RetentionCodec: Codec[Retention, Int] = new Codec[Retention, Int] {
    val Volatile                = 1
    val Eternal                 = 2
    val EternalInfrequentAccess = 3
    val Expiring                = 4
    val Persistent              = 5

    override def serialize(value: Retention): Int = value match {
      case Retention.Volatile                => Volatile
      case Retention.Eternal                 => Eternal
      case Retention.EternalInfrequentAccess => EternalInfrequentAccess
      case Retention.Expiring                => Expiring
      case Retention.Persistent              => Persistent
    }

    override def deserialize(value: Int): Retention = value match {
      case Volatile                => Retention.Volatile
      case Eternal                 => Retention.Eternal
      case EternalInfrequentAccess => Retention.EternalInfrequentAccess
      case Expiring                => Retention.Expiring
      case Persistent              => Retention.Persistent
    }
  }

  implicit val AssetUploadStatusCodec: Codec[AssetUploadStatus, Int] = new Codec[AssetUploadStatus, Int] {
    val NotStarted = 1
    val InProgress = 2
    val Done       = 3
    val Cancelled  = 4
    val Failed     = 5

    override def serialize(value: AssetUploadStatus): Int = value match {
      case AssetUploadStatus.NotStarted => NotStarted
      case AssetUploadStatus.InProgress => InProgress
      case AssetStatus.Done             => Done
      case AssetUploadStatus.Cancelled  => Cancelled
      case AssetUploadStatus.Failed     => Failed
    }

    override def deserialize(value: Int): AssetUploadStatus = value match {
      case NotStarted => AssetUploadStatus.NotStarted
      case InProgress => AssetUploadStatus.InProgress
      case Done       => AssetStatus.Done
      case Cancelled  => AssetUploadStatus.Cancelled
      case Failed     => AssetUploadStatus.Failed
    }
  }

  implicit val AssetDownloadStatusCodec: Codec[AssetDownloadStatus, Int] = new Codec[AssetDownloadStatus, Int] {
    val NotStarted = 1
    val InProgress = 2
    val Done       = 3
    val Cancelled  = 4
    val Failed     = 5

    override def serialize(value: AssetDownloadStatus): Int = value match {
      case AssetDownloadStatus.NotStarted => NotStarted
      case AssetDownloadStatus.InProgress => InProgress
      case AssetStatus.Done               => Done
      case AssetDownloadStatus.Cancelled  => Cancelled
      case AssetDownloadStatus.Failed     => Failed
    }

    override def deserialize(value: Int): AssetDownloadStatus = value match {
      case NotStarted => AssetDownloadStatus.NotStarted
      case InProgress => AssetDownloadStatus.InProgress
      case Done       => AssetStatus.Done
      case Cancelled  => AssetDownloadStatus.Cancelled
      case Failed     => AssetDownloadStatus.Failed
    }
  }

  implicit val AssetIdCodec: Codec[AssetId, String] = Codec.create(_.str, AssetId.apply)

  implicit val MessageIdCodec: Codec[MessageId, String] = Codec.create(_.str, MessageId.apply)

  implicit val RawAssetIdCodec: Codec[RawAssetId, String] = Codec.create(_.str, RawAssetId.apply)

  implicit val Sha256Codec: Codec[Sha256, Array[Byte]] = Codec.create(_.bytes, Sha256.apply)

  implicit val AssetTokenCodec: Codec[AssetToken, String] = Codec.create(_.str, AssetToken.apply)

  implicit val URICodec: Codec[URI, String] = Codec.create(_.toString, new URI(_))

  implicit val MimeCodec: Codec[Mime, String] = Codec.create(_.str, Mime.apply)

  implicit def JsonCodec[T: Encoder: Decoder]: Codec[T, String] =
    Codec.create(_.asJson.noSpaces, str => decode[T](str).right.get)

}
