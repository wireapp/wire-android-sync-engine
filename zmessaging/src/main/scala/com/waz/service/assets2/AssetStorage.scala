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

import android.content.Context
import com.waz.db.{ColumnBuilders, Dao}
import com.waz.model._
import com.waz.service.assets2.Asset._
import com.waz.service.assets2.AssetStorageImpl.AssetDao
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.wrappers.{DB, DBCursor}
import com.waz.utils.{CachedStorage2, CirceJSONSupport, DbStorage2, InMemoryStorage2, ReactiveStorage2, ReactiveStorageImpl2, TrimmingLruCache}
import io.circe.Decoder

import scala.concurrent.ExecutionContext

trait AssetStorage extends ReactiveStorage2[AssetId, Asset[General]]

class AssetStorageImpl(context: Context, db: DB, ec: ExecutionContext) extends ReactiveStorageImpl2(
  new CachedStorage2[AssetId, Asset[General]](
    new DbStorage2(AssetDao)(ec, db),
    new InMemoryStorage2[AssetId, Asset[General]](new TrimmingLruCache(context, Fixed(8)))(ec)
  )(ec)
) with AssetStorage

object AssetStorageImpl {

  object AssetDao extends Dao[Asset[General], AssetId] with ColumnBuilders[Asset[General]] with StorageCodecs with CirceJSONSupport {

    val dec = Decoder[LocalSource]

    val Id         = asText(_.id)('_id, "PRIMARY KEY")
    val Token      = asTextOpt(_.token)('token)
    val Type       = text(getAssetTypeString)('type)
    val Encryption = asText(_.encryption)('encryption)
    val Sha        = asBlob(_.sha)('sha)
    val Source     = asTextOpt(_.localSource)('source)
    val Preview    = asTextOpt(_.preview)('preview)
    val Details    = asText(_.details)('details)
    val MessageId  = asText(_.messageId)('message_id)
    val ConvId     = asTextOpt(_.convId)('conversation_id)

    override val idCol = Id
    override val table = Table("Assets", Id, Token, Type, Encryption, Sha, Source, Details, MessageId, ConvId)

    private val Image = "image"
    private val Audio = "audio"
    private val Video = "video"
    private val Blob  = "blob"

    override def apply(implicit cursor: DBCursor): Asset[General] = {
      Asset(Id, Token, Sha, Encryption, Source, Preview, Details, MessageId, ConvId)
    }

    private def getAssetTypeString(asset: Asset[General]): String = asset.details match {
      case _: Image => Image
      case _: Audio => Audio
      case _: Video => Video
      case _: Blob  => Blob
    }

  }

}
