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

import com.waz.model.Mime
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

  import com.waz.cache2.CacheService.{ AES_CBC_Encryption, Encryption, NoEncryption }
  import com.waz.model.{ AESKey, AssetId, AssetToken, Sha256 }
  import io.circe.parser._
  import io.circe.syntax._
  import io.circe.{ Decoder, Encoder }

  implicit val EncryptionCodec: Codec[Encryption, String] = new Codec[Encryption, String] {
    val Unencrypted    = ""
    val AES_CBC_Prefix = "AES_CBS__"
    val AES_GCM_Prefix = "AES_GCM__"

    override def serialize(value: Encryption): String = value match {
      case NoEncryption            => Unencrypted
      case AES_CBC_Encryption(key) => AES_CBC_Prefix + key.str
    }

    override def deserialize(value: String): Encryption = value match {
      case Unencrypted => NoEncryption
      case str if str.startsWith(AES_CBC_Prefix) =>
        AES_CBC_Encryption(AESKey(str.substring(AES_CBC_Prefix.length)))
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

  implicit val AssetIdCodec: Codec[AssetId, String] = Codec.create(_.str, AssetId.apply)

  implicit val Sha256Codec: Codec[Sha256, Array[Byte]] = Codec.create(_.bytes, Sha256.apply)

  implicit val AssetTokenCodec: Codec[AssetToken, String] = Codec.create(_.str, AssetToken.apply)

  implicit val URICodec: Codec[URI, String] = Codec.create(_.toString, new URI(_))

  implicit val MimeCodec: Codec[Mime, String] = Codec.create(_.str, Mime.apply)

  implicit def JsonCodec[T: Encoder: Decoder]: Codec[T, String] =
    Codec.create(_.asJson.noSpaces, str => decode[T](str).right.get)

}
