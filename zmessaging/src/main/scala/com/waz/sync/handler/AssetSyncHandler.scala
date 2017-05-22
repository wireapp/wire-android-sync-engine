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
package com.waz.sync.handler

import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse._
import com.waz.cache.{CacheService, LocalData}
import com.waz.model.AssetStatus.UploadInProgress
import com.waz.model._
import com.waz.service.assets.AssetService
import com.waz.sync.SyncResult
import com.waz.sync.client.AssetClient
import com.waz.sync.otr.OtrSyncHandler
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.znet.ZNetClient._

import scala.concurrent.Future
import scala.util.control.NoStackTrace

class AssetSyncHandler(cache: CacheService, client: AssetClient, assets: AssetService, otrSync: OtrSyncHandler) {

  import Threading.Implicits.Background

  private implicit val logTag: LogTag = logTagFor[AssetSyncHandler]

  //for v3
 def uploadAssetData(assetId: AssetId, public: Boolean = false): ErrorOrResponse[Option[AssetData]] = {
    CancellableFuture.lift(assets.updateAsset(assetId, _.copy(status = UploadInProgress)).zip(assets.getLocalData(assetId))) flatMap {
      case (Some(asset), Some(data)) if data.length > AssetData.MaxAllowedAssetSizeInBytes =>
        debug(s"Local data too big. Data length: ${data.length}, max size: ${AssetData.MaxAllowedAssetSizeInBytes}, local data: $data, asset: $asset")
        CancellableFuture successful Left(internalError(AssetSyncHandler.AssetTooLarge))
      case (Some(asset), _) if asset.remoteId.isDefined =>
        warn(s"asset has already been uploaded, skipping: $asset")
        CancellableFuture.successful(Right(None))
      case (Some(asset), Some(data)) =>
        otrSync.uploadAssetDataV3(data, if (public) None else Some(AESKey()), asset.mime).flatMap {
          case Right(remoteData) => CancellableFuture.lift(assets.updateAsset(asset.id, _.copyWithRemoteData(remoteData)).map { Right(_) })
          case Left(err) => CancellableFuture successful Left(err)
        }
      case (Some(asset), None) =>
        debug(s"An asset found, but its remote id is not defined, and local data is missing. Asset: $asset")
        CancellableFuture successful Right(None)
      case (None, Some(data)) =>
        debug(s"No asset data found, got local data: $data")
        CancellableFuture successful Right(None)
      case (None, None) =>
        debug(s"No asset data found, no local data found")
        CancellableFuture successful Right(None)
    }
  }

  //for v2
  def postSelfImageAsset(convId: RConvId, id: AssetId): Future[SyncResult] =
    (for {
      Some(asset) <- assets.getAssetData(id)
      data        <- cache.getEntry(asset.cacheKey)
    } yield (asset, data)) flatMap {
      case (a, Some(d)) => postImageData(convId, a, d, nativePush = false)
      case _ => Future.successful(SyncResult.Failure(None))
    }

  // plain text asset upload should only be used for self image
  private[handler] def postImageData(convId: RConvId, asset: AssetData, data: LocalData, nativePush: Boolean = true): Future[SyncResult] = {
    verbose(s"postImageData($convId, $asset, $data)")

    // save just posted data under new cacheKey, so we don't need to download it when image is accessed
    def updateImageCache(sent: AssetData) =
    if (sent.data64.isDefined) Future.successful(())
    else cache.addStream(sent.cacheKey, data.inputStream)

    client.postImageAssetData(asset, data, nativePush, convId).future flatMap {
      case Right(rId) =>
        verbose(s"postImageAssetData returned for ${asset.id} with local data: $data")
        for {
          _ <- updateImageCache(asset)
          _ <- assets.updateAsset(asset.id, _.copy(v2ProfileId = Some(rId)))
        } yield SyncResult.Success
      case Left(error) =>
        Future.successful(SyncResult(error))
    }
  }

}

class SyncException(msg: String) extends Exception(msg) with NoStackTrace

object AssetSyncHandler {
  val AssetTooLarge = "Failed to upload asset: Asset is too large"
}