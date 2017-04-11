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
package com.waz.sync.client

import java.io._

import com.waz.api.ProvisionedApiSpec
import com.waz.cache.LocalData
import com.waz.model.AssetData.RemoteData
import com.waz.model.AssetMetaData.Image.Tag.Medium
import com.waz.model.{Mime, _}
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.downloads.DownloadRequest.WireAssetRequest
import com.waz.service.images.BitmapSignal
import com.waz.sync.client.AssetClient.{Retention, UploadResponse}
import com.waz.testutils.DefaultPatienceConfig
import com.waz.testutils.Matchers._
import com.waz.threading.Threading
import com.waz.ui.MemoryImageCache.BitmapRequest
import com.waz.utils.events.EventContext
import com.waz.utils.{IoUtils, returning}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class AssetClientSpec extends FeatureSpec with Matchers with ProvisionedApiSpec with ScalaFutures with DefaultPatienceConfig {

  val provisionFile = "/one_conversation.json"

  implicit val timeout = 10.seconds: Timeout
  implicit lazy val ec = Threading.Background

  def client: AssetClient = zmessaging.assetClient

  feature("sending") {

    scenario("post image asset data") {
      val imageData = IoUtils.toByteArray(getClass.getResourceAsStream("/images/penguin_128.png"))

      val conversations = api.getConversations
      withDelay(conversations should not be empty)

      val c = ConvId(conversations.get(0).getId)
      val assetId = AssetId()
      val asset = new AssetData(metaData = Some(AssetMetaData.Image(Dim2(128, 128), Medium)), mime = Mime("image/png"), sizeInBytes = imageData.length, remoteId = Some(RAssetId()), data = Some(imageData))

      val response = for {
        conv <- zmessaging.convsStorage.get(c)
        res <- client.postImageAssetData(asset, LocalData(imageData), convId = conv.get.remoteId)
      } yield res

      val res = Await.result(response, 20.seconds)
      info(s"got response: $res")
      res should be('right)
      val Right(rId) = res
      //TODO Dean: no longer makes sense...
      rId shouldEqual asset.copy(data = None, remoteId = Some(rId))
    }

    scenario("post image asset") {
      val image = IoUtils.toByteArray(getClass.getResourceAsStream("/images/penguin.png"))

      val conversations = api.getConversations
      withDelay(conversations should not be empty)

      val c = conversations.get(0).asInstanceOf[com.waz.api.impl.Conversation].data.remoteId
      val input = api.ui.images.createImageAssetFrom(image).asInstanceOf[com.waz.api.impl.LocalImageAsset]
      val response = for {
        asset <- zmessaging.assetGenerator.generateWireAsset(input.data, profilePicture = false).future
        _ <- zmessaging.assets.updateAssets(Seq(asset))
        conv <- zmessaging.convsStorage.getByRemoteId(c)
        res <- {
          awaitUi(1.second)
          zmessaging.cache.getEntry(asset.cacheKey) flatMap {
            case Some(entry) => client.postImageAssetData(asset, entry, convId = conv.get.remoteId)
            case None => Future.successful(Left("meep"))
          }
        }
      } yield res

      val results = Await.result(response, 60.seconds)
      results should be('right)
    }

    scenario("post multiple assets") {
      val file = File.createTempFile("penguin", "png")
      IoUtils.copy(new ByteArrayInputStream(randomArray(14793)), file)

      val conversations = api.getConversations
      withDelay(conversations should not be empty)
      val c = conversations.get(0).asInstanceOf[com.waz.api.impl.Conversation].data.remoteId

      for (i <- 0 to 10) {
        withClue(i) {
          Await.result(client.postImageAssetData(AssetData(metaData = Some(AssetMetaData.Image(Dim2(100, 100), Medium)), mime = Mime("image/png"), sizeInBytes = file.length().toInt, remoteId = Some(RAssetId())), LocalData(file), nativePush = false, c), 5.seconds) shouldBe 'right
        }
      }
    }

    def randomArray(size: Int) = returning(new Array[Byte](size))(Random.nextBytes)
  }

  feature("assets v3 api") {
    lazy val image = IoUtils.toByteArray(getClass.getResourceAsStream("/images/penguin.png"))

    var asset: UploadResponse = null

    scenario("Upload an asset") {
      val resp = client.uploadAsset(LocalData(image), Mime.Image.Png, retention = Retention.Volatile).future.futureValue
      resp shouldBe 'right
      asset = resp.right.get
      asset.token shouldBe 'defined
    }

    scenario("Download the asset") {
      asset should not be null

      val res = zmessaging.assetLoader.getAssetData(new WireAssetRequest(CacheKey(), AssetId(), RemoteData(Some(asset.rId), asset.token), None, Mime.Image.Png)).future.futureValue
      res shouldBe defined
      IoUtils.toByteArray(res.get.inputStream).toSeq shouldEqual image.toSeq
    }

    scenario("Load asset using BitmapSignal") {
      val data = AssetData(mime = Mime.Image.Png, sizeInBytes = image.length, metaData = Some(AssetMetaData.Image(Dim2(480, 492), Medium))).copyWithRemoteData(RemoteData(Some(asset.rId), asset.token))

      val signal = BitmapSignal(data, BitmapRequest.Regular(600), zmessaging.imageLoader, zmessaging.imageCache)
      var results = Seq.empty[BitmapResult]
      signal { res =>
        results = results :+ res
      } (EventContext.Global)

      withDelay {
        results should not be empty
        results.last should beMatching({
          case BitmapResult.BitmapLoaded(bmp, _) if bmp.getWidth == 480 => true
        })
      }
    }
  }
}
