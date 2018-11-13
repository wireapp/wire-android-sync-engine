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

import java.io.File

import com.waz.cache2.{FileCache, LruFileCache}
import com.waz.model.{AssetId, AssetIdGeneral, RawAssetId}
import com.waz.utils.events.EventContext

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait AssetCache extends FileCache[AssetIdGeneral]

class AssetCacheImpl(val cacheDirectory: File, val directorySizeThreshold: Long, val sizeCheckingInterval: FiniteDuration)
                    (implicit val ec: ExecutionContext, val ev: EventContext) extends LruFileCache[AssetIdGeneral] with AssetCache {

  override protected def createFileName(key: AssetIdGeneral): String = key match {
    case AssetId(str) => "asset_" + str
    case RawAssetId(str) => "raw_asset_" + str
  }

}
