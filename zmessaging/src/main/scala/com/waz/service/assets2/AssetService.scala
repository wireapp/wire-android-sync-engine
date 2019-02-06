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

import java.io.InputStream
import java.security.{DigestInputStream, MessageDigest}

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model._
import com.waz.model.errors._
import com.waz.service.assets2.Asset.{General, UploadGeneral, Video}
import com.waz.sync.client.AssetClient2.{AssetContent, Metadata, Retention, UploadResponse2}
import com.waz.sync.client.{AssetClient2, ErrorOrResponse}
import com.waz.threading.CancellableFuture
import com.waz.utils.IoUtils
import com.waz.utils.events.Signal
import com.waz.utils.streams.CountInputStream
import com.waz.znet2.http.HttpClient._
import com.waz.znet2.http.ResponseCode

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

trait AssetService {
  def assetSignal(id: AssetIdGeneral): Signal[GeneralAsset]
  def assetStatusSignal(id: AssetIdGeneral): Signal[(AssetStatus, Option[Progress])]
  def downloadProgress(id: DownloadAssetId): Signal[Progress]
  def uploadProgress(id: UploadAssetId): Signal[Progress]

  def cancelUpload(id: UploadAssetId): Unit
  def cancelDownload(id: DownloadAssetId): Unit

  def getAsset(id: AssetId): Future[Asset[General]]

  def save(asset: GeneralAsset): Future[Unit]
  def delete(id: AssetIdGeneral): Future[Unit]

  def loadContentById(assetId: AssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def loadContent(asset: Asset[General], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def uploadAsset(rawAssetId: UploadAssetId): CancellableFuture[Asset[General]]
  def createAndSavePreview(rawAsset: UploadAsset[General]): Future[UploadAsset[General]]
  def createAndSaveRawAsset(content: ContentForUpload,
                            targetEncryption: Encryption,
                            public: Boolean,
                            retention: Retention,
                            messageId: Option[MessageId]): Future[UploadAsset[General]]
  def loadPublicContentById(assetId: PublicAssetId, convId: Option[ConvId], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def loadUploadContentById(uploadAssetId: UploadAssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
}

class AssetServiceImpl(assetsStorage: AssetStorage,
                       uploadAssetStorage: UploadAssetStorage,
                       downloadAssetStorage: DownloadAssetStorage,
                       assetDetailsService: AssetDetailsService,
                       previewService: AssetPreviewService,
                       uriHelper: UriHelper,
                       contentCache: AssetContentCache,
                       rawContentCache: RawAssetContentCache,
                       assetClient: AssetClient2)
                      (implicit ec: ExecutionContext) extends AssetService {


  override def assetSignal(idGeneral: AssetIdGeneral): Signal[GeneralAsset] =
    (idGeneral match {
      case id: AssetId => assetsStorage.signal(id)
      case id: UploadAssetId => uploadAssetStorage.signal(id)
      case id: DownloadAssetId => downloadAssetStorage.signal(id)
    }).map(a => a: GeneralAsset)

  override def assetStatusSignal(idGeneral: AssetIdGeneral): Signal[(AssetStatus, Option[Progress])] =
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

  override def cancelUpload(id: UploadAssetId): Unit = () //TODO

  override def cancelDownload(id: DownloadAssetId): Unit = () //TODO

  override def getAsset(id: AssetId): Future[Asset[General]] =
    assetsStorage.get(id)

  def getPublicAsset(id: PublicAssetId): Future[Option[Asset[General]]] =
    assetsStorage.find(AssetId(id.toString))

  override def save(asset: GeneralAsset): Future[Unit] = asset match {
    case a: Asset[General] => assetsStorage.save(a)
    case a: UploadAsset[UploadGeneral] => uploadAssetStorage.save(a)
    case a: DownloadAsset => downloadAssetStorage.save(a)
  }


  override def delete(idGeneral: AssetIdGeneral): Future[Unit] = idGeneral match {
    case id: AssetId => assetsStorage.deleteByKey(id)
    case id: UploadAssetId => uploadAssetStorage.deleteByKey(id)
    case id: DownloadAssetId => downloadAssetStorage.deleteByKey(id)
  }

  private def loadFromBackend(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(s"Load asset content from backend. $asset")
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
          debug(s"Loaded file size ${fileWithSha.file.length()}")
          CancellableFuture.failed(new ValidationError(s"SHA256 is not equal. Expected: ${asset.sha} Actual: ${fileWithSha.sha256} AssetId: ${asset.id}"))
        case Right(fileWithSha) =>
          contentCache.put(asset.id, fileWithSha.file, removeOriginal = true)
            .flatMap(_ => contentCache.getStream(asset.id).map(asset.encryption.decrypt(_)))
            .toCancellable
      }
      .recoverWith { case err =>
        verbose(s"Can not load asset content from backend. ${err.getMessage}")
        CancellableFuture.failed(err)
      }
  }

  private def loadFromCache(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(s"Load asset content from cache. $asset")
    contentCache.getStream(asset.id).map(asset.encryption.decrypt(_))
      .recoverWith { case err =>
        verbose(s"Can not load asset content from cache. $err")
        Future.failed(err)
      }
      .toCancellable
  }

  private def loadFromFileSystem(asset: Asset[General],
                                 localSource: LocalSource,
                                 callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(s"Load asset content from file system. $asset")
    lazy val emptyUriError = new NoSuchElementException("Asset does not have local source property.")
    val openInputStream = () => asset.localSource.map(ls => uriHelper.openInputStream(ls.uri)).getOrElse(Failure(throw emptyUriError))
    Future.fromTry(openInputStream())
      .flatMap(is => Future.fromTry(Sha256.calculate(is)))
      .flatMap { sha =>
        if (localSource.sha == sha) Future.fromTry(openInputStream())
        else Future.failed(new ValidationError(s"SHA256 is not equal. Expected: ${localSource.sha} Actual: $sha AssetId: ${asset.id}"))
      }
      .recoverWith { case err =>
        debug(s"Can not load content from file system. ${err.getMessage}")
        verbose(s"Clearing local source asset property. $asset")
        assetsStorage.save(asset.copy(localSource = None)).flatMap(_ => Future.failed(err))
      }
      .toCancellable
  }

  override def loadPublicContentById(assetId: PublicAssetId, convId: Option[ConvId], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    assetClient.loadPublicAssetContent(assetId, convId, callback).flatMap{
      case Left(err) => CancellableFuture.failed(err)
      case Right(i) => CancellableFuture.successful(i)
    }

  override def loadUploadContentById(uploadAssetId: UploadAssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    uploadAssetStorage.get(uploadAssetId).flatMap { asset =>
      (asset.assetId, asset.localSource) match {
        case (Some(aId), _) => loadContentById(aId, callback)
        case (_, Some(ls)) => Future.fromTry(uriHelper.openInputStream(ls.uri))
        case _ => CancellableFuture.failed(NotFoundLocal(""))
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

  override def uploadAsset(rawAssetId: UploadAssetId): CancellableFuture[Asset[General]] = {
    import com.waz.api.impl.ErrorResponse

    def getRawAssetContent(rawAsset: UploadAsset[General]): Future[InputStream] = rawAsset.localSource match {
      case Some(LocalSource(uri, _)) => Future.fromTry(uriHelper.openInputStream(uri))
      case None => rawContentCache.getStream(rawAsset.id)
    }

    def actionsOnCancellation(): Unit = {
      info(s"Asset uploading cancelled: $rawAssetId")
      uploadAssetStorage.update(rawAssetId, _.copy(status = UploadAssetStatus.Cancelled))
    }

    def loadRawAsset: Future[UploadAsset[General]] = uploadAssetStorage.get(rawAssetId).flatMap { rawAsset =>
      rawAsset.details match {
        case details: General =>
          CancellableFuture.successful(rawAsset.copy(details = details))
        case details =>
          CancellableFuture.failed(FailedExpectationsError(s"We expect that metadata already extracted. Got $details"))
      }
    }

    def doUpload(asset: UploadAsset[General]): ErrorOrResponse[UploadResponse2] = {
      val metadata = Metadata(asset.public, asset.retention)
      val content = AssetContent(
        asset.mime,
        asset.md5,
        () => getRawAssetContent(asset).map(asset.encryption.encrypt(_, asset.encryptionSalt)),
        Some(asset.size)
      )
      val uploadCallback: ProgressCallback = (p: Progress) => {
        uploadAssetStorage.save(asset.copy(uploaded = p.progress))
        ()
      }
      assetClient.uploadAsset(metadata, content, Some(uploadCallback))
    }

    def handleUploadResult(result: Either[ErrorResponse, UploadResponse2], rawAsset: UploadAsset[General]): Future[Asset[General]] =
      result match {
        case Left(err) =>
          uploadAssetStorage.update(rawAsset.id, _.copy(status = UploadAssetStatus.Failed)).flatMap(_ => Future.failed(err))
        case Right(response) =>
          val asset = Asset.create(response.key, response.token, rawAsset)
          for {
            _ <- assetsStorage.save(asset)
            _ <- uploadAssetStorage.update(rawAsset.id, _.copy(status = AssetStatus.Done, assetId = Some(asset.id)))
          } yield asset
      }

    def encryptAssetContentAndMoveToCache(asset: Asset[General]): Future[Unit] =
      if (asset.localSource.nonEmpty) Future.successful(())
      else for {
        rawContentStream <- rawContentCache.getStream(rawAssetId)
        _ <- contentCache.putStream(asset.id, asset.encryption.encrypt(rawContentStream))
        _ <- rawContentCache.remove(rawAssetId)
      } yield ()

    for {
      _ <- CancellableFuture.lift(Future.successful(()), actionsOnCancellation())
      rawAsset <- loadRawAsset.toCancellable
      Some((_, rawAsset: UploadAsset[General])) <- uploadAssetStorage.update(rawAsset.id, _.copy(uploaded = 0, status = UploadAssetStatus.InProgress)).toCancellable
      uploadResult <- doUpload(rawAsset)
      asset <- handleUploadResult(uploadResult, rawAsset).toCancellable
      _ <- encryptAssetContentAndMoveToCache(asset).toCancellable
    } yield asset
  }

  override def createAndSavePreview(uploadAsset: UploadAsset[General]): Future[UploadAsset[General]] = {
    def shouldAssetContainPreview: Boolean = uploadAsset.details match {
      case _: Video => true
      case _ => false
    }

    def getRawAssetContent: Future[CanExtractMetadata] = uploadAsset.localSource match {
      case Some(LocalSource(uri, _)) => Future.successful(Content.Uri(uri))
      case None => rawContentCache.get(uploadAsset.id).map(Content.File(uploadAsset.mime, _))
    }

    if (shouldAssetContainPreview) {
      for {
        rawAssetContent <- getRawAssetContent
        content <- previewService.extractPreview(uploadAsset, rawAssetContent)
        previewName = s"preview_for_${ uploadAsset.id.str}"
        contentForUpload = ContentForUpload(previewName, content)
        previewUploadAsset <- createRawAsset(contentForUpload, uploadAsset.encryption, uploadAsset.public, uploadAsset.retention)
        updatedUploadAsset = uploadAsset.copy(preview = RawPreviewNotUploaded(previewUploadAsset.id))
        _ <- uploadAssetStorage.save(previewUploadAsset)
        _ <- uploadAssetStorage.save(updatedUploadAsset)
      } yield updatedUploadAsset
    } else {
      for {
        updatedRawAsset <- Future.successful(uploadAsset.copy(preview = RawPreviewEmpty))
        _ <- uploadAssetStorage.save(updatedRawAsset)
      } yield updatedRawAsset
    }
  }

  override def createAndSaveRawAsset(content: ContentForUpload,
                                     targetEncryption: Encryption,
                                     public: Boolean,
                                     retention: Retention,
                                     messageId: Option[MessageId] = None): Future[UploadAsset[General]] = {
    for {
      rawAsset <- createRawAsset(content, targetEncryption, public, retention, messageId)
      _ = debug(s"Raw asset created: $rawAsset")
      _ <- uploadAssetStorage.save(rawAsset)
    } yield rawAsset
  }

  //TODO Sha256, Sha256 encrypted, size encrypted, md5 can be calculate at once by Streams composition
  private def createRawAsset(contentForUpload: ContentForUpload,
                             targetEncryption: Encryption,
                             public: Boolean,
                             retention: Retention,
                             messageId: Option[MessageId] = None): Future[UploadAsset[General]] = {

    val rawAssetId = UploadAssetId()
    val encryptionSalt = targetEncryption.randomSalt

    def prepareContent(content: Content): Future[CanExtractMetadata] = {
      content match {
        case content: Content.Uri => Future.successful(content)
        case Content.File(mime, file) =>
          for {
            _ <- rawContentCache.put(rawAssetId, file, removeOriginal = true)
            cacheFile <- rawContentCache.get(rawAssetId)
          } yield Content.File(mime, cacheFile)
        case Content.Bytes(mime, bytes) =>
          for {
            _ <- rawContentCache.putBytes(rawAssetId, bytes)
            file <- rawContentCache.get(rawAssetId)
          } yield Content.File(mime, file)
        case Content.AsBlob(content) => prepareContent(content)
      }
    }


    def extractLocalSourceAndEncryptedHashesAndSize(content: CanExtractMetadata): Try[(Option[LocalSource], Sha256, MD5, Long)] = {
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
      content <- prepareContent(contentForUpload.content)
      (localSource, encryptedSha, encryptedMd5, encryptedSize) <- Future.fromTry(extractLocalSourceAndEncryptedHashesAndSize(content))
      mime <- Future.fromTry(content.getMime(uriHelper))
      details <- contentForUpload.content match {
        case _: Content.AsBlob => Future.successful(BlobDetails)
        case _                 => assetDetailsService.extract(mime, content)
      }
    } yield UploadAsset(
      id = rawAssetId,
      localSource = localSource,
      name = contentForUpload.name,
      md5 = encryptedMd5,
      sha = encryptedSha,
      mime = mime,
      preview = RawPreviewNotReady,
      uploaded = 0,
      size = encryptedSize,
      retention = retention,
      public = public,
      encryption = targetEncryption,
      encryptionSalt = encryptionSalt,
      details = details,
      status = UploadAssetStatus.NotStarted,
      assetId = None
    )
  }

}