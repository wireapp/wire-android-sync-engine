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
import com.waz.service.assets2.InProgressAssetStorage.InProgressAssetDao
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.wrappers.{DB, DBCursor}
import com.waz.utils.{CachedStorage2, CirceJSONSupport, DbStorage2, InMemoryStorage2, ReactiveStorage2, ReactiveStorageImpl2, TrimmingLruCache}

import scala.concurrent.ExecutionContext

trait InProgressAssetStorage extends ReactiveStorage2[InProgressAssetId, InProgressAsset]

class InProgressAssetStorageImpl(context: Context, db: DB)(implicit ec: ExecutionContext)
  extends ReactiveStorageImpl2(
    new CachedStorage2(
      new DbStorage2(InProgressAssetDao)(ec, db),
      new InMemoryStorage2[InProgressAssetId, InProgressAsset](new TrimmingLruCache(context, Fixed(8)))(ec)
    )(ec)
  ) with InProgressAssetStorage

object InProgressAssetStorage {

  object InProgressAssetDao
    extends Dao[InProgressAsset, InProgressAssetId]
      with ColumnBuilders[InProgressAsset]
      with StorageCodecs
      with CirceJSONSupport {

    val Id             = asText(_.id)('_id, "PRIMARY KEY")
    val Mime           = asText(_.mime)('mime)
    val Downloaded     = long(_.downloaded)('downloaded)
    val Size           = long(_.size)('size)
    val Name           = text(_.name)('name)
    val Preview        = asText(_.preview)('preview)
    val Details        = asText(_.details)('details)
    val UploadStatus   = asInt(_.status)('status)

    override val idCol = Id
    override val table = Table("InProgressAssets", Id, Mime, Preview, Size, Details)

    override def apply(implicit cursor: DBCursor): InProgressAsset =
      InProgressAsset(Id, Mime, Name, Preview, Details, Downloaded, Size, UploadStatus)

  }

}
