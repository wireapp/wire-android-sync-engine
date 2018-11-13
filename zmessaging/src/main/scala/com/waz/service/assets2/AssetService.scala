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

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model._
import com.waz.model.errors._
import com.waz.service.assets2.Asset.{General, Video}
import com.waz.sync.client.AssetClient2
import com.waz.sync.client.AssetClient2.{AssetContent, Metadata, Retention}
import com.waz.threading.CancellableFuture
import com.waz.znet2.http.HttpClient._
import com.waz.znet2.http.ResponseCode

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

trait AssetService {
  def loadContentById(assetId: AssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def loadContent(asset: Asset[General], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream]
  def uploadAsset(rawAssetId: RawAssetId): CancellableFuture[Asset[General]]
  def createAndSavePreview(rawAsset: RawAsset[General]): Future[RawAsset[General]]
  def createAndSaveRawAsset(content: ContentForUpload,
                            targetEncryption: Encryption,
                            public: Boolean,
                            retention: Retention,
                            messageId: Option[MessageId]): Future[RawAsset[General]]
}

class AssetServiceImpl(assetsStorage: AssetStorage,
                       rawAssetStorage: RawAssetStorage,
                       assetDetailsService: AssetDetailsService,
                       previewService: AssetPreviewService,
                       uriHelper: UriHelper,
                       cache: AssetCache,
                       assetClient: AssetClient2)
                      (implicit ec: ExecutionContext) extends AssetService {

  private def loadFromBackend(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(s"Load asset content from backend. $asset")
    assetClient.loadAssetContent(asset, callback)
      .flatMap {
        case Left(err) if err.code == ResponseCode.NotFound =>
          cache
            .remove(asset.id)
            .flatMap(_ => CancellableFuture.failed(NotFoundRemote(s"Asset '$asset'")))
            .toCancellable
        case Left(err) =>
          CancellableFuture.failed(NetworkError(err))
        case Right(fileWithSha) if fileWithSha.sha256 != asset.sha =>
          CancellableFuture.failed(ValidationError(s"SHA256 is not equal. Asset: $asset"))
        case Right(fileWithSha) =>
          cache.put(asset.id, fileWithSha.file, removeOriginal = true)
            .flatMap(_ => cache.getStream(asset.id).map(asset.encryption.decrypt))
            .toCancellable
      }
      .recoverWith { case err =>
        verbose(s"Can not load asset content from backend. ${err.getMessage}")
        CancellableFuture.failed(err)
      }
  }

  private def loadFromCache(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(s"Load asset content from cache. $asset")
    cache.getStream(asset.id).map(asset.encryption.decrypt)
      .recoverWith { case err =>
        verbose(s"Can not load asset content from cache. $err")
        Future.failed(err)
      }
      .toCancellable
  }

  private def loadFromFileSystem(asset: Asset[General], callback: Option[ProgressCallback]): CancellableFuture[InputStream] = {
    verbose(s"Load asset content from file system. $asset")
    lazy val emptyUriError = new NoSuchElementException("Asset does not have local source property.")
    val openInputStream = () => asset.localSource.map(ls => uriHelper.openInputStream(ls.uri)).getOrElse(Failure(throw emptyUriError))
    Future.fromTry(openInputStream())
      .flatMap(is => Future.fromTry(Sha256.calculate(is)))
      .flatMap { sha =>
        if (asset.sha == sha) Future.fromTry(openInputStream())
        else Future.failed(ValidationError(s"SHA256 is not equal. Asset: $asset"))
      }
      .recoverWith { case err =>
        debug(s"Can not load content from file system. ${err.getMessage}")
        verbose(s"Clearing local source asset property. $asset")
        assetsStorage.save(asset.copy(localSource = None)).flatMap(_ => Future.failed(err))
      }
      .toCancellable
  }

  override def loadContentById(assetId: AssetId, callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    assetsStorage.get(assetId).flatMap(asset => loadContent(asset, callback)).toCancellable

  override def loadContent(asset: Asset[General], callback: Option[ProgressCallback] = None): CancellableFuture[InputStream] =
    assetsStorage.find(asset.id)
      .flatMap { fromStorage =>
        if (fromStorage.isEmpty)
          assetsStorage.save(asset).flatMap(_ => loadFromBackend(asset, callback))
        else if (asset.localSource.isEmpty)
          loadFromCache(asset, callback).recoverWith { case _ => loadFromBackend(asset, callback) }
        else
          loadFromFileSystem(asset, callback).recoverWith { case _ => loadFromBackend(asset, callback) }
      }
      .toCancellable

  override def uploadAsset(rawAssetId: RawAssetId): CancellableFuture[Asset[General]] = {
    for {
      _ <- CancellableFuture.lift(Future.successful(()), () => {
        info(s"Asset uploading cancelled: $rawAssetId")
        rawAssetStorage.update(rawAssetId, _.copy(uploadStatus = UploadStatus.Cancelled))
      })
      rawAsset <- CancellableFuture.lift(rawAssetStorage.get(rawAssetId).flatMap { rawAsset =>
        rawAsset.details match {
          case details: General =>
            CancellableFuture.successful(rawAsset.copy(details = details))
          case details =>
            CancellableFuture.failed(FailedExpectationsError(s"We expect that metadata already extracted. Got $details"))
        }
      })
      _ <- CancellableFuture.lift(rawAssetStorage.update(rawAsset.id, _.copy(uploaded = 0, uploadStatus = UploadStatus.InProgress)))
      metadata = Metadata(rawAsset.public, rawAsset.retention)
      is <- cache.getStream(rawAsset.id).toCancellable
      content = AssetContent(rawAsset.mime, () => is, Some(rawAsset.size)) //TODO Maybe AssetContent should take Future[InputStream]
      uploadCallback: ProgressCallback = (p: Progress) => {
        rawAssetStorage.save(rawAsset.copy(uploaded = p.progress))
        ()
      }
      uploadResult <- assetClient.uploadAsset(metadata, content, Some(uploadCallback))
      asset <- (uploadResult match {
        case Left(err) =>
          rawAssetStorage.update(rawAsset.id, _.copy(uploadStatus = UploadStatus.Failed)).flatMap(_ => Future.failed(err))
        case Right(response) =>
          val asset = Asset.create(response.key, response.token, rawAsset)
          for {
            _ <- assetsStorage.save(asset)
            _ <- rawAssetStorage.update(rawAsset.id, _.copy(uploadStatus = UploadStatus.Done, assetId = Some(asset.id)))
          } yield asset
      }).toCancellable
      _ <- CancellableFuture.lift(
        if (asset.localSource.nonEmpty) cache.remove(rawAsset.id)
        else cache.changeKey(rawAsset.id, asset.id)
      )
    } yield asset
  }

  override def createAndSavePreview(rawAsset: RawAsset[General]): Future[RawAsset[General]] = {
    def shouldAssetContainPreview: Boolean = rawAsset.details match {
      case _: Video => true
      case _ => false
    }

    if (shouldAssetContainPreview) {
      for {
        content <- previewService.extractPreview(rawAsset, cache.getStream(rawAsset.id).map(rawAsset.encryption.decrypt))
        previewRawAsset <- createRawAsset(content, rawAsset.encryption, rawAsset.public, rawAsset.retention)
        updatedRawAsset = rawAsset.copy(preview = RawPreviewNotUploaded(previewRawAsset.id))
        _ <- rawAssetStorage.save(updatedRawAsset)
      } yield updatedRawAsset
    } else {
      for {
        updatedRawAsset <- Future.successful(rawAsset.copy(preview = RawPreviewEmpty))
        _ <- rawAssetStorage.save(updatedRawAsset)
      } yield updatedRawAsset
    }
  }

  override def createAndSaveRawAsset(content: ContentForUpload,
                                     targetEncryption: Encryption,
                                     public: Boolean,
                                     retention: Retention,
                                     messageId: Option[MessageId] = None): Future[RawAsset[General]] = {
    for {
      rawAsset <- createRawAsset(content, targetEncryption, public, retention, messageId)
      _ <- rawAssetStorage.save(rawAsset)
    } yield rawAsset
  }

  private def createRawAsset(content: ContentForUpload,
                             targetEncryption: Encryption,
                             public: Boolean,
                             retention: Retention,
                             messageId: Option[MessageId] = None): Future[RawAsset[General]] = {
    val rawAssetId = RawAssetId()
    def putContentInCacheGetSizeWithSha: Future[(Long, Sha256)] = {
      for {
        contentStream <- content match {
          case ContentForUpload.Bytes(_, _, bytes) => Future.successful(new ByteArrayInputStream(bytes))
          case ContentForUpload.Uri(_, _, uri) => Future.fromTry(uriHelper.openInputStream(uri))
          case ContentForUpload.File(_, _, encrFile) => Future(encrFile.encryption.decrypt(new FileInputStream(encrFile.file)))
        }
        _ <- cache.putStream(rawAssetId, targetEncryption.encrypt(contentStream))
        encryptedContentSize <- cache.getFileSize(rawAssetId)
        encryptedContent <- cache.getStream(rawAssetId)
        encryptedContentSha <- Future.fromTry(Sha256.calculate(encryptedContent))
      } yield encryptedContentSize -> encryptedContentSha
    }
    def createLocalSource: Future[Option[LocalSource]] = Future {
      Some(content)
        .collect { case ContentForUpload.Uri(_, _, uri) =>
          LocalSource(uri, uriHelper.openInputStream(uri).flatMap(Sha256.calculate).get)
        }
    }

    (assetDetailsService.extract(content) zip createLocalSource zip putContentInCacheGetSizeWithSha)
      .map { case ((details, localSource), (encryptedContentSize, encryptedContentSha)) =>
        RawAsset(
          id = rawAssetId,
          localSource = localSource,
          name = content.name,
          sha = encryptedContentSha,
          mime = content.mime,
          preview = RawPreviewNotReady,
          uploaded = 0,
          size = encryptedContentSize,
          retention = retention,
          public = public,
          encryption = targetEncryption,
          details = details,
          uploadStatus = UploadStatus.NotStarted,
          assetId = None,
          messageId = messageId
        )
      }
  }

}