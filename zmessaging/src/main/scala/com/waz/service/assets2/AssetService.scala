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

import java.io.{FileOutputStream, InputStream}
import java.security.{DigestInputStream, MessageDigest}

import com.waz.ZLog.ImplicitTag._
import com.waz.model.AssetData.UploadTaskKey
import com.waz.model._
import com.waz.log.ZLog2._
import com.waz.model.errors._
import com.waz.service.assets2.Asset.{General, UploadGeneral, Video}
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.AssetClient2.{AssetContent, Metadata, Retention, UploadResponse2}
import com.waz.sync.client.{AssetClient2, ErrorOrResponse}
import com.waz.threading.CancellableFuture
import com.waz.utils.events.Signal
import com.waz.utils.streams.CountInputStream
import com.waz.utils.{Cancellable, IoUtils}
import com.waz.znet2.http.HttpClient._
import com.waz.znet2.http.ResponseCode

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

trait AssetService {
  def assetSignal(id: GeneralAssetId): Signal[GeneralAsset]
  def assetStatusSignal(id: GeneralAssetId): Signal[(AssetStatus, Option[Progress])]
  def downloadProgress(id: DownloadAssetId): Signal[Progress]
  def uploadProgress(id: UploadAssetId): Signal[Progress]

  def cancelUpload(id: UploadAssetId, message: MessageData): Future[Unit]
  def cancelDownload(id: DownloadAssetId): Unit

  def getAsset(id: AssetId): Future[Asset[General]]

  def save(asset: GeneralAsset): Future[Unit]
  def delete(id: GeneralAssetId): Future[Unit]

  def loadContentById(assetId: AssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def loadContent(asset: Asset[General], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def uploadAsset(id: UploadAssetId): CancellableFuture[Asset[General]]
  def createAndSavePreview(asset: UploadAsset[General]): Future[UploadAsset[General]]
  def createAndSaveUploadAsset(content: ContentForUpload,
                               targetEncryption: Encryption,
                               public: Boolean,
                               retention: Retention,
                               messageId: Option[MessageId]): Future[UploadAsset[General]]
  def loadPublicContentById(assetId: AssetId, convId: Option[ConvId], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def loadUploadContentById(uploadAssetId: UploadAssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
}

class AssetServiceImpl(assetsStorage: AssetStorage,
                       uploadAssetStorage: UploadAssetStorage,
                       downloadAssetStorage: DownloadAssetStorage,
                       assetDetailsService: AssetDetailsService,
                       previewService: AssetPreviewService,
                       transformations: AssetTransformationsService,
                       restrictions: AssetRestrictionsService,
                       uriHelper: UriHelper,
                       contentCache: AssetContentCache,
                       uploadContentCache: UploadAssetContentCache,
                       assetClient: AssetClient2,
                       sync: SyncServiceHandle)
                      (implicit ec: ExecutionContext) extends AssetService {


  override def assetSignal(idGeneral: GeneralAssetId): Signal[GeneralAsset] =
    (idGeneral match {
      case id: AssetId => assetsStorage.signal(id)
      case id: UploadAssetId => uploadAssetStorage.signal(id)
      case id: DownloadAssetId => downloadAssetStorage.signal(id)
    }).map(a => a: GeneralAsset)

  override def assetStatusSignal(idGeneral: GeneralAssetId): Signal[(AssetStatus, Option[Progress])] =
    assetSignal(idGeneral) map {
      case _: Asset[General] => AssetStatus.Done -> None
      case asset: UploadAsset[UploadGeneral] =>
        asset.status match {
          case AssetStatus.Done => asset.status -> None
          case UploadAssetStatus.NotStarted => asset.status -> None
          case _ => asset.status -> Some(Progress(asset.uploaded, Some(asset.size)))
        }
      case asset: DownloadAsset =>
        asset.status match {
          case AssetStatus.Done => asset.status -> None
          case DownloadAssetStatus.NotStarted => asset.status -> None
          case _ => asset.status -> Some(Progress(asset.downloaded, Some(asset.size)))
        }
    }

  override def downloadProgress(id: DownloadAssetId): Signal[Progress] =
    assetStatusSignal(id).collect { case (_, Some(progress)) => progress }

  override def uploadProgress(id: UploadAssetId): Signal[Progress] =
    assetStatusSignal(id).collect { case (_, Some(progress)) => progress }

  override def cancelUpload(id: UploadAssetId, message: MessageData): Future[Unit] = {
    import scala.concurrent.duration._
    for {
      _ <- Cancellable.cancel(UploadTaskKey(id))
      _ <- CancellableFuture.timeout(3.seconds).future
      _ <- uploadAssetStorage.update(id, asset => {
        if (asset.status == AssetStatus.Done) asset
        else asset.copy(status = UploadAssetStatus.Cancelled)
      })
      _ <- sync.postAssetStatus(message.id, message.convId, message.ephemeral, UploadAssetStatus.Cancelled)
    } yield ()
  }

  override def cancelDownload(id: DownloadAssetId): Unit = () //TODO

  override def getAsset(id: AssetId): Future[Asset[General]] =
    assetsStorage.get(id)

  override def save(asset: GeneralAsset): Future[Unit] = asset match {
    case a: Asset[General] => assetsStorage.save(a)
    case a: UploadAsset[UploadGeneral] => uploadAssetStorage.save(a)
    case a: DownloadAsset => downloadAssetStorage.save(a)
  }


  override def delete(idGeneral: GeneralAssetId): Future[Unit] = idGeneral match {
    case id: AssetId => assetsStorage.deleteByKey(id)
    case id: UploadAssetId => uploadAssetStorage.deleteByKey(id)
    case id: DownloadAssetId => downloadAssetStorage.deleteByKey(id)
  }

  private def loadFromBackend(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(l"Load asset content from backend. $asset")
    assetClient.loadAssetContent(asset, callback)
      .flatMap {
        case Left(err) if err.code == ResponseCode.NotFound =>
          contentCache
            .remove(asset.id)
            .flatMap(_ => CancellableFuture.failed(NotFoundRemote(s"Asset '$asset'")))
            .toCancellable
        case Left(err) =>
          CancellableFuture.failed(NetworkError(err))
        case Right(fileWithSha) if fileWithSha.sha256 != asset.sha =>
          debug(l"Loaded file size ${fileWithSha.file.length()}")
          CancellableFuture.failed(new ValidationError(s"SHA256 is not equal. Expected: ${asset.sha} Actual: ${fileWithSha.sha256} AssetId: ${asset.id}"))
        case Right(fileWithSha) =>
          contentCache.put(asset.id, fileWithSha.file, removeOriginal = true)
            .flatMap(_ => contentCache.getStream(asset.id).map(asset.encryption.decrypt(_)))
            .toCancellable
      }
      .recoverWith { case err =>
        verbose(l"Can not load asset content from backend. ${showString(err.getMessage)}")
        CancellableFuture.failed(err)
      }
  }

  private def loadFromCache(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(l"Load asset content from cache. $asset")
    contentCache.getStream(asset.id).map(asset.encryption.decrypt(_))
      .recoverWith { case err =>
        verbose(l"Can not load asset content from cache. $err")
        Future.failed(err)
      }
      .toCancellable
  }

  private def loadFromFileSystem(asset: Asset[General],
                                 localSource: LocalSource,
                                 callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(l"Load asset content from file system. $asset")
    lazy val emptyUriError = new NoSuchElementException("Asset does not have local source property.")
    val openInputStream = () => asset.localSource.map(ls => uriHelper.openInputStream(ls.uri)).getOrElse(Failure(throw emptyUriError))
    Future.fromTry(openInputStream())
      .flatMap(is => Future.fromTry(Sha256.calculate(is)))
      .flatMap { sha =>
        if (localSource.sha == sha) Future.fromTry(openInputStream())
        else Future.failed(new ValidationError(s"SHA256 is not equal. Expected: ${localSource.sha} Actual: $sha AssetId: ${asset.id}"))
      }
      .recoverWith { case err =>
        debug(l"Can not load content from file system. ${showString(err.getMessage)}")
        verbose(l"Clearing local source asset property. $asset")
        assetsStorage.save(asset.copy(localSource = None)).flatMap(_ => Future.failed(err))
      }
      .toCancellable
  }

  override def loadPublicContentById(assetId: AssetId, convId: Option[ConvId], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    assetClient.loadPublicAssetContent(assetId, convId, callback).flatMap{
      case Left(err) => CancellableFuture.failed(err)
      case Right(i) => CancellableFuture.successful(i)
    }

  override def loadUploadContentById(uploadAssetId: UploadAssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    uploadAssetStorage.get(uploadAssetId).flatMap { asset =>
      (asset.assetId, asset.localSource) match {
        case (Some(aId), _) => loadContentById(aId, callback)
        case (_, Some(ls)) => Future.fromTry(uriHelper.openInputStream(ls.uri))
        case _ => uploadContentCache.getStream(uploadAssetId)
      }
    }.toCancellable

  override def loadContentById(assetId: AssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    assetsStorage.get(assetId).flatMap(asset => loadContent(asset, callback)).toCancellable

  override def loadContent(asset: Asset[General], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    assetsStorage.find(asset.id)
      .flatMap { fromStorage =>
        if (fromStorage.isEmpty)
          assetsStorage.save(asset).flatMap(_ => loadFromBackend(asset, callback))
        else asset.localSource match {
          case Some(source) =>
            loadFromFileSystem(asset, source, callback).recoverWith { case _ => loadFromBackend(asset, callback) }
          case None =>
            loadFromCache(asset, callback).recoverWith { case _ => loadFromBackend(asset, callback) }
        }
      }
      .toCancellable

  override def uploadAsset(assetId: UploadAssetId): CancellableFuture[Asset[General]] = {
    import com.waz.api.impl.ErrorResponse

    def getUploadAssetContent(asset: UploadAsset[General]): Future[InputStream] = asset.localSource match {
      case Some(LocalSource(uri, _)) => Future.fromTry(uriHelper.openInputStream(uri))
      case None => uploadContentCache.getStream(asset.id)
    }

    def actionsOnCancellation(): Unit = {
      info(l"Asset uploading cancelled: $assetId")
      uploadAssetStorage.update(assetId, _.copy(status = UploadAssetStatus.Cancelled))
    }

    def loadUploadAsset: Future[UploadAsset[General]] = uploadAssetStorage.get(assetId).flatMap { asset =>
      asset.details match {
        case details: General =>
          CancellableFuture.successful(asset.copy(details = details))
        case details =>
          CancellableFuture.failed(FailedExpectationsError(s"We expect that metadata already extracted. Got $details"))
      }
    }

    def doUpload(asset: UploadAsset[General]): ErrorOrResponse[UploadResponse2] = {
      val metadata = Metadata(asset.public, asset.retention)
      val content = AssetContent(
        asset.mime,
        asset.md5,
        () => getUploadAssetContent(asset).map(asset.encryption.encrypt(_, asset.encryptionSalt)),
        Some(asset.size)
      )
      val uploadCallback: ProgressCallback = new FilteredProgressCallback(
        ProgressFilter.steps(100, asset.size),
        new ProgressCallback {
          override def updated(progress: Long, total: Option[Long]): Unit = {
            uploadAssetStorage.update(asset.id, _.copy(uploaded = progress))
            ()
          }
        }
      )
      assetClient.uploadAsset(metadata, content, Some(uploadCallback))
    }

    def handleUploadResult(result: Either[ErrorResponse, UploadResponse2], uploadAsset: UploadAsset[General]): Future[Asset[General]] =
      result match {
        case Left(err) =>
          uploadAssetStorage.update(uploadAsset.id, _.copy(status = UploadAssetStatus.Failed)).flatMap(_ => Future.failed(err))
        case Right(response) =>
          val asset = Asset.create(response.key, response.token, uploadAsset)
          for {
            _ <- assetsStorage.save(asset)
            _ <- uploadAssetStorage.update(uploadAsset.id, _.copy(status = AssetStatus.Done, assetId = Some(asset.id)))
          } yield asset
      }

    def encryptAssetContentAndMoveToCache(asset: Asset[General]): Future[Unit] =
      if (asset.localSource.nonEmpty) Future.successful(())
      else for {
        contentStream <- uploadContentCache.getStream(assetId)
        _ <- contentCache.putStream(asset.id, asset.encryption.encrypt(contentStream))
        _ <- uploadContentCache.remove(assetId)
      } yield ()

    for {
      _ <- CancellableFuture.lift(Future.successful(()), actionsOnCancellation())
      uploadAsset <- loadUploadAsset.toCancellable
      Some((_, uploadAsset: UploadAsset[General])) <- uploadAssetStorage.update(uploadAsset.id, _.copy(uploaded = 0, status = UploadAssetStatus.InProgress)).toCancellable
      uploadResult <- doUpload(uploadAsset)
      asset <- handleUploadResult(uploadResult, uploadAsset).toCancellable
      _ <- encryptAssetContentAndMoveToCache(asset).toCancellable
    } yield asset
  }

  override def createAndSavePreview(uploadAsset: UploadAsset[General]): Future[UploadAsset[General]] = {
    def shouldAssetContainPreview: Boolean = uploadAsset.details match {
      case _: Video => true
      case _ => false
    }

    def getPreparedAssetContent: Future[PreparedContent] = uploadAsset.localSource match {
      case Some(LocalSource(uri, _)) => Future.successful(Content.Uri(uri))
      case None => uploadContentCache.get(uploadAsset.id).map(Content.File(uploadAsset.mime, _))
    }

    if (shouldAssetContainPreview) {
      for {
        uploadAssetContent <- getPreparedAssetContent
        content <- previewService.extractPreview(uploadAsset, uploadAssetContent)
        previewName = s"preview_for_${ uploadAsset.id.str}"
        contentForUpload = ContentForUpload(previewName, content)
        previewUploadAsset <- createUploadAsset(contentForUpload, uploadAsset.encryption, uploadAsset.public, uploadAsset.retention)
        updatedUploadAsset = uploadAsset.copy(preview = Preview.NotUploaded(previewUploadAsset.id))
        _ <- uploadAssetStorage.save(previewUploadAsset)
        _ <- uploadAssetStorage.save(updatedUploadAsset)
      } yield updatedUploadAsset
    } else {
      for {
        updatedUploadAsset <- Future.successful(uploadAsset.copy(preview = Preview.Empty))
        _ <- uploadAssetStorage.save(updatedUploadAsset)
      } yield updatedUploadAsset
    }
  }

  override def createAndSaveUploadAsset(content: ContentForUpload,
                                        targetEncryption: Encryption,
                                        public: Boolean,
                                        retention: Retention,
                                        messageId: Option[MessageId] = None): Future[UploadAsset[General]] = {
    val t0 = System.nanoTime()
    for {
      asset <- createUploadAsset(content, targetEncryption, public, retention, messageId)
      _ = debug(l"Upload asset created: $asset")
      _ <- uploadAssetStorage.save(asset)
      t1 = System.nanoTime()
      _ = println("Asset creation time: " + (t1 - t0) + " ns")
    } yield asset
  }

  private def createUploadAsset(contentForUpload: ContentForUpload,
                                targetEncryption: Encryption,
                                public: Boolean,
                                retention: Retention,
                                messageId: Option[MessageId] = None): Future[UploadAsset[General]] = {

    val assetId = UploadAssetId()
    val encryptionSalt = targetEncryption.randomSalt

    def prepareContent(content: Content): Future[PreparedContent] = {
      content match {
        case content: Content.Uri => Future.successful(content)
        case Content.File(mime, file) =>
          for {
            _ <- uploadContentCache.put(assetId, file, removeOriginal = true)
            cacheFile <- uploadContentCache.get(assetId)
          } yield Content.File(mime, cacheFile)
        case Content.Bytes(mime, bytes) =>
          for {
            _ <- uploadContentCache.putBytes(assetId, bytes)
            file <- uploadContentCache.get(assetId)
          } yield Content.File(mime, file)
        case Content.AsBlob(content) => prepareContent(content)
      }
    }

    def extractDetails(content: PreparedContent, mime: Mime): Future[AssetDetails] = contentForUpload.content match {
      case _: Content.AsBlob => Future.successful(BlobDetails)
      case _                 => assetDetailsService.extract(mime, content)
    }

    def extractLocalSourceAndEncryptedHashesAndSize(content: PreparedContent): Try[(Option[LocalSource], Sha256, MD5, Long)] = {
      val sha256DigestWithUri = content match {
        case Content.Uri(uri) => Some(MessageDigest.getInstance("SHA-256") -> uri)
        case _ => None
      }
      val sha256EncryptedDigest = MessageDigest.getInstance("SHA-256")
      val md5EncryptedDigest = MessageDigest.getInstance("MD5")

      for {
        source <- content.openInputStream(uriHelper)

        stream1 = sha256DigestWithUri.fold(source) { case (digest, _) => new DigestInputStream(source, digest) }
        stream2 = targetEncryption.encrypt(stream1, encryptionSalt)
        stream3 = new DigestInputStream(stream2, sha256EncryptedDigest)
        stream4 = new DigestInputStream(stream3, md5EncryptedDigest)
        stream5 = new CountInputStream(stream4)

        _ = IoUtils.readFully(stream5)

        localSource = sha256DigestWithUri.map { case (digest, uri) => LocalSource(uri, Sha256(digest.digest())) }
        encryptedSha = Sha256(sha256EncryptedDigest.digest())
        encryptedMd5 = MD5(md5EncryptedDigest.digest())
        encryptedSize = stream5.getBytesRead
      } yield (localSource, encryptedSha, encryptedMd5, encryptedSize)
    }

    for {
      initialContent <- prepareContent(contentForUpload.content)
      initialMime <- Future.fromTry(initialContent.getMime(uriHelper))
      initialDetails <- extractDetails(initialContent, initialMime)
      ts = transformations.getTransformations(initialMime, initialDetails)
      //TODO Handle not only the first transformation
      (transformedContent, transformedMime, transformedDetails) <- ts.headOption match {
        case Some(transformation) => for {
          cacheFile <- uploadContentCache.getOrCreateEmpty(assetId)
          mime <- Future { transformation(() =>
            initialContent.openInputStream(uriHelper).get, () => new FileOutputStream(cacheFile))
          }
          content = Content.File(mime, cacheFile)
          details <- extractDetails(content, mime)
        } yield (content, mime, details)
        case _ =>
          Future.successful((initialContent, initialMime, initialDetails))
      }
      _ <- Future.fromTry(restrictions.validate(transformedContent))
      (localSource, encryptedSha, encryptedMd5, encryptedSize) <- Future.fromTry(extractLocalSourceAndEncryptedHashesAndSize(transformedContent))
    } yield UploadAsset(
      id = assetId,
      localSource = localSource,
      name = contentForUpload.name,
      md5 = encryptedMd5,
      sha = encryptedSha,
      mime = transformedMime,
      preview = Preview.NotReady,
      uploaded = 0,
      size = encryptedSize,
      retention = retention,
      public = public,
      encryption = targetEncryption,
      encryptionSalt = encryptionSalt,
      details = transformedDetails,
      status = UploadAssetStatus.NotStarted,
      assetId = None
    )
  }

}