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
import com.waz.service.PreferenceServiceImpl
import com.waz.service.assets.AssetService
import com.waz.service.conversation.{ConversationEventsService, DefaultConversationsContentUpdater}
import com.waz.service.images.ImageLoader
import com.waz.sync.SyncResult
import com.waz.sync.client.AssetClient
import com.waz.sync.otr.OtrSyncHandler
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.znet.ZNetClient._

import scala.concurrent.Future
import scala.util.control.NoStackTrace

class AssetSyncHandler(cache: CacheService, convs: DefaultConversationsContentUpdater, convEvents: ConversationEventsService, client: AssetClient,
                       assets: AssetService, imageLoader: ImageLoader, otrSync: OtrSyncHandler, prefs: PreferenceServiceImpl) {

  import Threading.Implicits.Background

  private implicit val logTag: LogTag = logTagFor[AssetSyncHandler]

  //for v3
  def uploadAssetData(assetId: AssetId, public: Boolean = false): ErrorOrResponse[Option[AssetData]] = {
    CancellableFuture.lift(assets.storage.updateAsset(assetId, _.copy(status = UploadInProgress)).zip(assets.getAssetData(assetId))) flatMap {
      case (Some(asset), Some(data)) if data.length > AssetData.MaxAllowedAssetSizeInBytes =>
        CancellableFuture successful Left(internalError(AssetSyncHandler.AssetTooLarge))
      case (Some(asset), _) if asset.remoteId.isDefined =>
        warn(s"asset has already been uploaded, skipping: $asset")
        CancellableFuture.successful(Right(None))
      case (Some(asset), Some(data)) =>
        otrSync.uploadAssetDataV3(data, if (public) None else Some(AESKey()), asset.mime).flatMap {
          case Right(remoteData) =>
            for {
              Some(updated) <- CancellableFuture.lift(assets.storage.updateAsset(asset.id, _.copyWithRemoteData(remoteData)))
              _ <- CancellableFuture.lift(cache.addStream(updated.cacheKey, data.inputStream, updated.mime, updated.name, length = data.length))
            } yield Right(Some(updated))
          case Left(err) => CancellableFuture successful Left(err)
        }
      case asset =>
        debug(s"No asset data found in postAssetData, got: $asset")
        CancellableFuture successful Right(None)
    }
  }

  //for v2
  def postSelfImageAsset(convId: RConvId, id: AssetId): Future[SyncResult] =
    (for {
      Some(asset) <- assets.storage.get(id)
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
          _ <- assets.storage.updateAsset(asset.id, _.copy(v2ProfileId = Some(rId)))
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