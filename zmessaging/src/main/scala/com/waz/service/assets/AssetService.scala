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
package com.waz.service.assets

import java.io._
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import com.waz.ZLog._
import com.waz.api.Permission
import com.waz.api.ProgressIndicator.State
import com.waz.api.impl.ProgressIndicator.ProgressData
import com.waz.api.impl.{AssetForUpload, AudioAssetForUpload, ContentUriAssetForUpload, TranscodedVideoAsset}
import com.waz.cache.{CacheEntry, CacheService, Expiration, LocalData}
import com.waz.content.WireContentProvider.CacheUri
import com.waz.content._
import com.waz.model.AssetData.{FetchKey, UploadKey}
import com.waz.model.AssetStatus.Order._
import com.waz.model.AssetStatus.{UploadCancelled, UploadDone, UploadFailed, UploadInProgress, UploadNotStarted}
import com.waz.model.ErrorData.AssetError
import com.waz.model.GenericContent.Asset
import com.waz.model._
import com.waz.service.ErrorsService
import com.waz.service.assets.GlobalRecordAndPlayService.AssetMediaKey
import com.waz.service.downloads.DownloadRequest._
import com.waz.service.downloads._
import com.waz.service.images.ImageAssetGenerator
import com.waz.sync.SyncServiceHandle
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.events.Signal
import com.waz.{Analytics, PermissionsService, api}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration._
import scala.util.Random

class AssetService(val storage: AssetsStorage, generator: ImageAssetGenerator, cache: CacheService, context: Context,
    loader: AssetLoader, messages: MessagesStorage, downloader: DownloaderService, errors: ErrorsService,
    permissions: PermissionsService, streamLoader: Downloader[AssetFromInputStream], assetDownloader: AssetDownloader,
    metaService: MetaDataService, previewService: PreviewService, sync: SyncServiceHandle, media: GlobalRecordAndPlayService) {

  import AssetService._
  import com.waz.threading.Threading.Implicits.Background
  import com.waz.utils.events.EventContext.Implicits.global

  assetDownloader.onDownloadDone {
    case AnyAssetRequest(_, id, _, _, _, _) => markDownloadDone(id)
    case _ =>
  }

  assetDownloader.onDownloadFailed {
    case (AnyAssetRequest(_, id, _, _, _, _), _) => markDownloadFailed(id)
    case _ =>
  }

  messages.onMessageFailed { case (m, _) =>
    if (m.isAssetMessage) markUploadFailed(m.assetId, UploadFailed)
  }

  messages.onDeleted { msgs => storage.remove(msgs.map(id => AssetId(id.str))) }

  storage.onDeleted { assets => media.releaseAnyOngoing(assets.map(AssetMediaKey)(breakOut)) }

  errors.onErrorDismissed {
    case AssetError(ms) => Future.traverse(ms) { messages.delete }
  }

  def assetSignal(id: AssetId) = storage.signal(id).flatMap[(AnyAssetData, api.AssetStatus)] {
    case asset @ AnyAssetData(_, _, _, _, _, meta, _, _, _, AssetStatus(status, Some(_)), _) =>
      cache.cacheStorage.optSignal(asset.cacheKey).map(_.isDefined) flatMap {
        case true  =>
          verbose(s"asset in state DOWNLOAD_DONE, meta: $meta")
          if (meta.isEmpty) metaService.getAssetMetadata(id) // asset downloaded but has no metadata, let's update
          Signal const (asset, api.AssetStatus.DOWNLOAD_DONE)
        case false =>
          downloader.getDownloadState(asset.cacheKey).map(_.state) map {
            case State.RUNNING    => (asset, api.AssetStatus.DOWNLOAD_IN_PROGRESS)
            case State.COMPLETED  => (asset, api.AssetStatus.DOWNLOAD_IN_PROGRESS) // reporting asset in progress since it should be added to cache before we change the state
            case _                => (asset, status)
          }
      }
    case asset @ AnyAssetData(_, _, _, _, _, _, _, _, _, st, _) => Signal const (asset, st.status)
    case _ => Signal.empty[(AnyAssetData, api.AssetStatus)]
  }

  def downloadProgress(id: AssetId) = storage.signal(id) flatMap {
    case asset: AnyAssetData =>
      downloader.getDownloadState(asset.cacheKey)
    case _ =>
      // TODO: support image asset progress
      Signal const ProgressData.Unknown
  }

  def cancelDownload(id: AssetId) = storage.get(id) flatMap {
    case Some(asset: AnyAssetData) => downloader.cancel(asset.loadRequest)
    case asset =>
      verbose(s"cancel not supported for asset: $asset")
      // TODO: support image asset
      successful(())
  }

  def uploadProgress(id: AssetId) = Signal const ProgressData.Indefinite // TODO

  def cancelUpload(id: AssetId, msg: MessageId): Future[Unit] =
    for {
      _ <- downloader.cancel(id.str)
      _ <- Cancellable.cancel(FetchKey(id))
      _ <- Cancellable.cancel(UploadKey(id))
      _ <- markUploadFailed(id, UploadCancelled)
    } yield ()

  def markUploadFailed(id: AssetId, status: AssetStatus.Syncable) =
    storage.updateAsset(id, { a => if (a.status > UploadInProgress) a else a.copy(status = status) }) flatMap {
      case Some(updated) =>
        storage.onUploadFailed ! updated
        messages.get(MessageId(id.str)) flatMap {
          case Some(m) => sync.postAssetStatus(m.id, m.convId, m.ephemeral, status)
          case None =>
            warn(s"No message found for asset upload: $id")
            Future.successful(())
        }
      case _ =>
        Future.successful(())
    }

  def updateAsset(id: AssetId, convId: RConvId, asset: Asset, dataId: Option[RAssetDataId], time: Date): Future[AssetData] =
    storage.updateOrCreate(id, {
      case data @ AnyAssetData(`id`, `convId`, _, _, _, _, _, _, _, _, _) => data.updated(AnyAssetData(id, convId, asset, dataId, time.instant))
      case data =>
        Analytics.saveException(new Exception(s"Unexpected asset data in updateAsset()"), s"data: $data, asset: $id, conv: $convId")
        data
    }, AnyAssetData(id, convId, asset, dataId, time.instant))

  def updateImageAsset(asset: ImageAssetData): Future[AssetData] = storage.insert(asset)

  def updateImageAsset(id: AssetId, convId: RConvId, image: ImageData): Future[AssetData] = {
    storage.updateOrCreate(id, {
      case im @ ImageAssetData(`id`, _, _) => im.updated(image).copy(convId = convId)
      case data =>
        Analytics.saveException(new Exception(s"Unexpected asset data in updateImageAsset()"), s"data: $data, asset: $id, conv: $convId")
        data
    }, ImageAssetData(id, convId, Seq(image)))
  }

  def updateImageAssets(data: Seq[ImageAssetData]) =
    storage.updateOrCreateAll(data.map(d => d.id -> { (_: Option[AssetData]) => d })(collection.breakOut))

  def addImageAsset(assetId: AssetId, image: com.waz.api.ImageAsset, convId: RConvId, isSelf: Boolean): Future[ImageAssetData] = {
    image match {
      case im: com.waz.api.impl.ImageAsset if im.data.versions.nonEmpty =>
        val ref = new AtomicReference(image) // keep a strong reference until asset generation completes
        generator.generateWireAsset(assetId, im.data.versions.last, convId, isSelf).future.flatMap { data =>
          updateImageAssets(Seq(data)) map (_ => data)
        } andThen { case _ => ref set null }
      case _ =>
        failed(new IllegalArgumentException(s"Unsupported ImageAsset: $image"))
    }
  }

  def getAssetData(id: AssetId): CancellableFuture[Option[LocalData]] =
    CancellableFuture lift storage.get(id) flatMap {
      case None => CancellableFuture successful None
      case Some(a @ AnyAssetData(_, convId, mime, _, name, _,_, _, _, UploadDone(key), _)) =>
        loader.getAssetData(AnyAssetRequest(a.cacheKey, id, convId, key, mime, name))
      case Some(a @ AnyAssetData(_, _, mime, _, name, _, _, Some(uri), originalMime, _, _)) =>
        loader.getAssetData(LocalAssetRequest(a.cacheKey, uri, originalMime.getOrElse(mime), name)) map { res =>
          if (res.isEmpty) errors.addAssetFileNotFoundError(id)
          res
        }
      case Some(a: AnyAssetData) =>
        CancellableFuture lift cache.getEntry(a.cacheKey)
      case _ => CancellableFuture successful None // TODO: should we support image assets in here?
    }

  def assetDataOrSource(asset: AnyAssetData): CancellableFuture[Option[Either[LocalData, Uri]]] =
    CancellableFuture lift cache.getEntry(asset.cacheKey) flatMap {
      case Some(entry) => CancellableFuture successful Some(Left(entry))
      case None =>
        asset.source match {
          case Some(uri) => CancellableFuture successful Some(Right(uri))
          case None =>
            getAssetData(asset.id) map { _.map(Left(_)) }
        }
    }


  def addAsset(a: AssetForUpload, conv: RConvId): Future[AssetData] = {
    def addAssetFromUri(id: AssetId, uri: Uri, originalMimeType: Option[Mime], metaData: Option[AssetMetaData]) = {
      // it's enough to create AnyAssetData with input uri, everything else can be updated lazily
      verbose(s"addAsset from uri: $uri")
      for {
        mime  <- a.mimeType
        size  <- a.sizeInBytes
        name  <- a.name
        asset <- storage.insert(AnyAssetData(id, conv, mime, size.getOrElse(0), name, uri, Instant.EPOCH, originalMimeType, metaData))
      } yield {
        verbose(s"asset added: $asset")
        // metadata and preview will be needed in UI, let's start generating them already
        metaService.getAssetMetadata(id) flatMap { _ => previewService.getAssetPreview(id) }
        asset
      }
    }

    a match {
      case ContentUriAssetForUpload(id, uri) =>
        addAssetFromUri(id, uri, None, None)

      case AudioAssetForUpload(id, entry, duration, _) =>
        addAssetFromUri(id, CacheUri(entry.data, context), Some(Mime.Audio.PCM), Some(AssetMetaData.Audio(duration)))

      case _ =>

        def fetchAssetData(mime: Mime, name: Option[String]) = (a match {
          case TranscodedVideoAsset(_, data) => CancellableFuture lift cache.move(a.id.str, data, Mime.Video.MP4, if (mime == Mime.Video.MP4) name else name.map(_ + ".mp4"), cacheLocation = Some(cache.cacheDir)) map { Some(_) }
          case _                             => downloader.download(AssetFromInputStream(a.id.str, () => a.openDataStream(context), mime, name))(streamLoader)
        }).flatMap {
          case Some(entry) => CancellableFuture.successful(entry)
          case None        =>
            errors.addAssetFileNotFoundError(a.id)
            CancellableFuture.failed(new NoSuchElementException("no cache entry after download"))
        }

        def fetchAsset(m: Mime, n: Option[String], asset: AnyAssetData) = for {
          entry    <- fetchAssetData(m, n)
          (mime, name) = entry match {
            case e: CacheEntry => (e.data.mimeType, e.data.fileName.orElse(n))
            case _ => (m, n)
          }
          meta     <- metaService.loadMetaData(mime, entry)
          _        =  verbose(s"meta data for ${a.id}: $meta")
          preview  <- previewService.loadPreview(a.id, mime, entry)
          _        =  verbose(s"preview for ${a.id}: $preview")
          updated  <- CancellableFuture lift storage.updateAsset(asset.id, _.copy(metaData = meta, mimeType = mime, name = name, preview = preview, sizeInBytes = entry.length))
        } yield updated

        for {
          m        <- a.mimeType
          size     <- a.sizeInBytes
          n        <- a.name
          asset    =  AnyAssetData(a.id, conv, m, size.getOrElse(0), n, None, None, None, None, UploadNotStarted, Instant.EPOCH)
          _        <- storage.insert(asset)
          updated  <- Cancellable(FetchKey(a.id))(fetchAsset(m, n, asset)).future
        } yield
          updated.getOrElse(asset)
    }
  }

  def markDownloadFailed(id: AssetId) = storage.updateAsset(id, _.withDownloadFailed())

  def markDownloadDone(id: AssetId) = storage.updateAsset(id, _.clearDownloadState())

  def getAssetUri(id: AssetId): CancellableFuture[Option[Uri]] =
    CancellableFuture.lift(storage.get(id)) .flatMap {
      case Some(a: AnyAssetData) =>
        loader.getAssetData(a.loadRequest) flatMap {
          case Some(entry: CacheEntry) =>
            CancellableFuture successful Some(CacheUri(entry.data, context))
          case Some(data) =>
            CancellableFuture lift cache.addStream(Uid().str, data.inputStream, a.mimeType, a.name, Some(cache.intCacheDir))(Expiration.in(12.hours)) map { e => Some(CacheUri(e.data, context)) }
          case None =>
            CancellableFuture successful None
        }
      case _ =>
        warn(s"asset not found: $id")
        CancellableFuture successful None
    }

  def saveAssetToDownloads(id: AssetId): Future[Option[File]] = storage.getAsset(id).flatMapOpt(saveAssetToDownloads)

  def saveAssetToDownloads(asset: AnyAssetData): Future[Option[File]] = {

    def nextFileName(baseName: String, retry: Int) =
      if (retry == 0) baseName else s"${retry}_$baseName"

    def getTargetFile(dir: File): Option[File] = {
      val baseName = asset.name.getOrElse("wire_downloaded_file." + asset.mimeType.extension) // XXX: should get default file name form resources
      // prepend a number to the name to get unique file name,
      // will try sequential numbers from 0 - 10 first, and then fallback to random ones
      // will give up after 100 tries
      val prefix = ((0 to 10).iterator ++ Iterator.continually(Random.nextInt(10000))).take(100)
      prefix.map(i => new File(dir, nextFileName(baseName, i))).find(!_.exists())
    }

    def saveAssetData(file: File) =
      loader.getAssetData(asset.loadRequest).future.map {
        case Some(data) =>
          IoUtils.copy(data.inputStream, new FileOutputStream(file))
          Some(file)
        case None =>
          None
      } (Threading.IO)

    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (dir.isDirectory)
      permissions.requiring(Set(Permission.WRITE_EXTERNAL_STORAGE), delayUntilProviderIsSet = false) (
        {
          warn("permission to save asset to downloads denied")
          successful(None)
        },
        getTargetFile(dir).fold(successful(Option.empty[File]))(saveAssetData)
      )
    else
      successful(None)
  }

}

object AssetService {
  private implicit val logTag: LogTag = logTagFor[AssetService]

  val SaveImageDirName = "Wire"

  lazy val SaveImageDir = {
    val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + File.separator + SaveImageDirName
    val dir = new File(path)
    dir.mkdirs()
    dir
  }

  def assetDir(context: Context) = new File(context.getFilesDir, "assets")

  def sanitizeFileName(name: String) = name.replace(' ', '_').replaceAll("[^\\w]", "")

  def saveImageFile(mime: Mime) = new File(SaveImageDir,  s"wire_${System.currentTimeMillis}.${mime.extension}")

  sealed trait BitmapResult
  object BitmapResult {
    case object Empty extends BitmapResult
    case class BitmapLoaded(bitmap: Bitmap, preview: Boolean, etag: Int = 0) extends BitmapResult {
      override def toString: LogTag = s"BitmapLoaded([${bitmap.getWidth}, ${bitmap.getHeight}], $preview, $etag)"
    }
    case class LoadingFailed(ex: Throwable) extends BitmapResult
  }

  sealed trait BitmapRequest {
    val width: Int
    val mirror: Boolean = false
  }
  object BitmapRequest {
    case class Regular(width: Int, override val mirror: Boolean = false) extends BitmapRequest
    case class Static(width: Int, override val mirror: Boolean = false) extends BitmapRequest
    case class Single(width: Int, override val mirror: Boolean = false) extends BitmapRequest
    case class Round(width: Int, borderWidth: Int = 0, borderColor: Int = 0) extends BitmapRequest
  }
}
