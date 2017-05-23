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
package com.waz.service.downloads

import java.io.InputStream

import com.waz.model.{Mime, _}
import com.waz.utils.wrappers.URI
import com.waz.znet.Request

sealed trait DownloadRequest {
  val cacheKey: CacheKey
}

object DownloadRequest {

  sealed trait AssetRequest extends DownloadRequest {
    val mime: Mime
    val name: Option[String]
  }

  case class CachedAssetRequest(cacheKey: CacheKey, mime: Mime, name: Option[String]) extends AssetRequest

  case class LocalAssetRequest(cacheKey: CacheKey, uri: URI, mime: Mime, name: Option[String]) extends AssetRequest

  sealed trait ExternalAssetRequest extends AssetRequest {
    def request: Request[Unit]

    override val mime: Mime = Mime.Unknown
    override val name: Option[String] = None
  }

  case class WireAssetRequest(cacheKey: CacheKey, assetId: AssetId, remoteData: AssetData.RemoteData, convId: Option[RConvId], mime: Mime, name: Option[String] = None) extends AssetRequest

  case class AssetFromInputStream(cacheKey: CacheKey, stream: () => InputStream, mime: Mime = Mime.Unknown, name: Option[String] = None) extends DownloadRequest

  case class VideoAsset(cacheKey: CacheKey, uri: URI, mime: Mime = Mime.Unknown, name: Option[String] = None) extends DownloadRequest

  case class UnencodedAudioAsset(cacheKey: CacheKey, name: Option[String]) extends DownloadRequest

  case class External(cacheKey: CacheKey, uri: URI) extends ExternalAssetRequest {
    override def request: Request[Unit] = Request[Unit](baseUri = Some(uri), requiresAuthentication = false)
  }

  // external asset downloaded from wire proxy, path is relative to our proxy endpoint
  case class Proxied(cacheKey: CacheKey, path: String) extends ExternalAssetRequest {
    override def request: Request[Unit] = Request.Get(path)
  }
}
