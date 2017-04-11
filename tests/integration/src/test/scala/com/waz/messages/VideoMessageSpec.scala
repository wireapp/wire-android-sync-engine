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
package com.waz.messages

import java.io.{ByteArrayInputStream, File, InputStream}

import akka.pattern.ask
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever.{METADATA_KEY_DURATION, METADATA_KEY_VIDEO_HEIGHT, METADATA_KEY_VIDEO_ROTATION, METADATA_KEY_VIDEO_WIDTH}
import android.net.Uri
import com.waz.api._
import com.waz.api.impl.DoNothingAndProceed
import com.waz.cache._
import com.waz.content.WireContentProvider.CacheUri
import com.waz.model.otr.ClientId
import com.waz.model.{Mime, AssetStatus => _, MessageContent => _, _}
import com.waz.provision.ActorMessage.{AwaitSyncCompleted, Login, Successful}
import com.waz.service
import com.waz.service.{UserModule, ZMessagingFactory}
import com.waz.sync.client.AssetClient
import com.waz.sync.client.AssetClient.{OtrAssetMetadata, OtrAssetResponse}
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import com.waz.testutils.{DefaultPatienceConfig, ReusableCountDownLatch}
import com.waz.threading.Threading
import com.waz.threading.Threading.Implicits.Background
import com.waz.utils.IoUtils.toByteArray
import com.waz.utils._
import com.waz.znet.ZNetClient._
import org.robolectric.Robolectric.{getShadowApplication, shadowOf}
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.threeten.bp

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

class VideoMessageSpec extends FeatureSpec with Matchers with BeforeAndAfter with OptionValues with ProvisionedApiSpec with ThreadActorSpec with ScalaFutures with Inspectors with DefaultPatienceConfig {
  scenario("init remote") {
    auto2 ? Login(provisionedEmail("auto2"), "auto2_pass") should eventually(be(Successful))
    auto2 ? AwaitSyncCompleted should eventually(be(Successful))
    (messages should have size 1).soon
    conv.sendMessage(new MessageContent.Text("meep"))
    (messages should have size 2).soon
  }

  feature("Sending a video asset message") {
    scenario("Blue sky") {
      val fromBefore = messages.size
      val video = videoForUpload
      isDownloadingFromProvider += video.cacheKey

      conv.sendMessage(new MessageContent.Asset(video, DoNothingAndProceed))

      within(1.second)(messages should have size (fromBefore + 1))

      lazy val message = messages.getLastMessage
      lazy val asset = message.getAsset
      lazy val preview = message.getImage

      soon {
        asset.isEmpty shouldBe false
        asset.isVideo shouldBe true
        asset.getDuration shouldEqual videoDuration
        asset.getWidth shouldEqual unrotatedVideoDimensions.width
        asset.getHeight shouldEqual unrotatedVideoDimensions.height
        preview.getWidth shouldEqual previewBitmap.getWidth
        preview.getHeight shouldEqual previewBitmap.getHeight
        message.getImageWidth shouldEqual previewBitmap.getWidth
        message.getImageHeight shouldEqual previewBitmap.getHeight
        asset.getStatus shouldEqual AssetStatus.UPLOAD_NOT_STARTED // preview should be avaible before sync even started
      }

      within(10.seconds) {
        asset.getStatus shouldEqual AssetStatus.DOWNLOAD_DONE
        asset.isVideo shouldBe true
        asset.getDuration shouldEqual videoDuration
        asset.getWidth shouldEqual unrotatedVideoDimensions.width
        asset.getHeight shouldEqual unrotatedVideoDimensions.height
        preview.getWidth shouldEqual previewBitmap.getWidth
        preview.getHeight shouldEqual previewBitmap.getHeight
        message.getImageWidth shouldEqual previewBitmap.getWidth
        message.getImageHeight shouldEqual previewBitmap.getHeight
      }
    }

    scenario("Cancel preview upload") {
      val fromBefore = messages.size
      val video = videoForUpload
      isDownloadingFromProvider += video.cacheKey

      lazy val msg = messages.getLastMessage
      lazy val asset = msg.getAsset

      reusableLatch.ofSize(1) { latch =>
        conv.sendMessage(new MessageContent.Asset(video, DoNothingAndProceed))
        within(1.second)(messages should have size (fromBefore + 1))

        soon {
          asset should not be empty
          postStarted shouldBe true
        }
        latch.countDown()
        asset.getUploadProgress.cancel()
      }

      idle(5.seconds)

      postCancelled shouldBe true
      asset.getStatus shouldEqual AssetStatus.UPLOAD_CANCELLED
      messages should have size fromBefore
    }

    scenario("Cancel after preview upload") {
      val fromBefore = messages.size
      val video = videoForUpload
      isDownloadingFromProvider += video.cacheKey

      lazy val msg = messages.getLastMessage
      lazy val asset = msg.getAsset

      reusableLatch.ofSize(1) { latch =>
        conv.sendMessage(new MessageContent.Asset(video, DoNothingAndProceed))
        within(1.second)(messages should have size (fromBefore + 1))

        soon {
          asset should not be empty
          postStarted shouldBe true
        }
        postStarted = false
        latch.countDown() // for preview
      }

      reusableLatch.ofSize(1) { latch =>
        (postStarted shouldBe true).soon
        latch.countDown() // for full upload
        asset.getUploadProgress.cancel()
      }

      idle(5.seconds)

      postCancelled shouldBe true
      asset.getStatus shouldEqual AssetStatus.UPLOAD_CANCELLED
      messages should have size fromBefore
    }
  }

  before {
    ShadowMediaMetadataRetriever.reset()
    isDownloadingFromProvider = Set.empty
    postStarted = false
  }

  after {
    awaitUi(zmessaging.syncRequests.content.listSyncJobs.await().isEmpty)
  }

  lazy val conversations = api.getConversations
  lazy val self = api.getSelf
  lazy val conv = conversations.head
  lazy val messages = conv.getMessages
  lazy val resolver = shadowOf(getShadowApplication.getContentResolver)

  lazy val auto2 = registerDevice("auto2")

  def contentCacheKey(asset: com.waz.api.Asset) = {
    val p = Promise[CacheKey]
    asset.getContentUri(new com.waz.api.Asset.LoadCallback[Uri]() {
      override def onLoaded(uri: Uri): Unit = CacheUri.unapply(context)(uri) match {
        case Some(key) => p.success(key)
        case None => p.failure(new Exception(s"Returned uri is not CacheUri: $uri"))
      }
      override def onLoadFailed(): Unit = p.failure(new Exception("loading failed"))
    })
    p.future
  }

  lazy val videoRaw = toByteArray(getClass.getResourceAsStream("/assets/video_hd.mp4"))
  lazy val previewBitmap = Managed(getClass.getResourceAsStream("/images/video_preview.jpg")).acquire(BitmapFactory.decodeStream)
  lazy val videoDuration = bp.Duration.ofMillis(3093)
  lazy val videoDimensions = Dim2(1920, 1080)
  lazy val videoRotation = 90
  lazy val unrotatedVideoDimensions = Dim2(videoDimensions.height, videoDimensions.width)

  def videoForUpload = impl.AssetForUpload(AssetId(), Some("video_hd"), Mime.Video.MP4, Some(videoRaw.length))(_ => new ByteArrayInputStream(videoRaw))

  override val provisionFile: String = "/two_users_connected.json"

  override lazy val globalModule = new ApiSpecGlobal {
    override lazy val cache = new CacheService(context, storage) {
      override def addStream[A](key: CacheKey, in: => InputStream, mime: Mime = Mime.Unknown, name: Option[String] = None, cacheLocation: Option[File] = None, length: Int = -1, execution: ExecutionContext = Background)(implicit timeout: Expiration = CacheService.DefaultExpiryTime): Future[CacheEntry] =
        super.addStream(key, in, mime, name, cacheLocation, length, execution)(timeout).andThen {
          case Success(entry) if isDownloadingFromProvider(key) =>
            val path = entry.cacheFile.getAbsolutePath
            info(s"meta data registered for path: $path")
            ShadowMediaMetadataRetriever.addMetadata(path, METADATA_KEY_VIDEO_WIDTH, videoDimensions.width.toString)
            ShadowMediaMetadataRetriever.addMetadata(path, METADATA_KEY_VIDEO_HEIGHT, videoDimensions.height.toString)
            ShadowMediaMetadataRetriever.addMetadata(path, METADATA_KEY_VIDEO_ROTATION, videoRotation.toString)
            ShadowMediaMetadataRetriever.addMetadata(path, METADATA_KEY_DURATION, videoDuration.toMillis.toString)
            ShadowMediaMetadataRetriever.addFrame(path, -1L, previewBitmap)
        }
    }
  }

  @volatile private var isDownloadingFromProvider = Set.empty[CacheKey]
  @volatile private var postStarted = false
  @volatile private var postCancelled = false
  private val reusableLatch = new ReusableCountDownLatch

}

