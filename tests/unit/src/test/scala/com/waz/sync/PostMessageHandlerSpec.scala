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
package com.waz.sync

import java.util.Date

import android.database.sqlite.SQLiteDatabase
import com.waz._
import com.waz.api.impl.ErrorResponse
import com.waz.api.{Message, NetworkMode}
import com.waz.model.AssetMetaData.Image
import com.waz.model.AssetMetaData.Image.Tag.Medium
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.Asset
import com.waz.model.{Mime, _}
import com.waz.service._
import com.waz.sync.client.MessagesClient.OtrMessage
import com.waz.sync.client.OtrClient.ClientMismatch
import com.waz.sync.handler.AssetSyncHandler
import com.waz.sync.otr.{OtrSyncHandler, OtrSyncHandlerImpl}
import com.waz.sync.queue.ConvLock
import com.waz.testutils.Matchers._
import com.waz.testutils.MockZMessaging
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.events.EventContext
import com.waz.znet.ZNetClient.ErrorOrResponse
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLog
import org.scalatest._
import org.threeten.bp.Instant
import org.threeten.bp.Instant.now

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

@Ignore class PostMessageHandlerSpec extends FeatureSpec with Matchers with BeforeAndAfter with GivenWhenThen with RobolectricTests with RobolectricUtils { test =>

  private lazy implicit val dispatcher = Threading.Background
  private lazy implicit val ev = EventContext.Global

  implicit def db: SQLiteDatabase = zms.db.dbHelper.getWritableDatabase

  type PostMessageReq = (RConvId, OtrMessage, Boolean)

  var postMessageResponse: Future[Either[ErrorResponse, Date]] = _
  var postImageResult: Either[ErrorResponse, Option[AssetData]] = _

  def handler = zms.messagesSync
  def storage = zms.storage.db

  lazy val userId = UserId()
  lazy val conv = ConversationData(ConvId(), RConvId(), None, userId, ConversationType.Group)

  implicit lazy val lock = ConvLock(conv.id, zms.syncRequests.scheduler.queue)

  lazy val zms: MockZMessaging = new MockZMessaging(selfUserId = userId) {

    usersStorage.addOrOverwrite(UserData(test.userId, "selfUser"))

    override lazy val otrSync: OtrSyncHandler = new OtrSyncHandlerImpl(otrClient, messagesClient, assetClient, otrService, assets, conversations, convsStorage, users, messages, errors, otrClientsSync, cache, pushSignals) {
      override def postOtrMessage(convId: ConvId, remoteId: RConvId, message: GenericMessage, recipients: Option[Set[UserId]], nativePush: Boolean) = postMessageResponse
    }

    override lazy val network: DefaultNetworkModeService = new DefaultNetworkModeService(context, lifecycle) {
      override def updateNetworkMode(): Unit = ()
    }

    override lazy val assetSync = new AssetSyncHandler(cache, assetClient, assets, otrSync) {
      override def uploadAssetData(assetId: AssetId, public: Boolean): ErrorOrResponse[Option[AssetData]] = CancellableFuture successful postImageResult
    }

    insertConv(conv)
  }

  def sendingTimeout = zms.timeouts.messages.sendingTimeout

  def response(cm: ClientMismatch, ignoreMissing: Boolean = false) =
    CancellableFuture.successful {
      if (cm.missing.nonEmpty && !ignoreMissing) Left(cm) else Right(cm)
    }

  before {
    zms.network.networkMode ! NetworkMode.WIFI
    postMessageResponse = Future.successful(Left(ErrorResponse(500, "", "")))
    postImageResult = Right(Some(AssetData.Empty))
  }

  after {
    ShadowLog.stream = null
    storage.close()
    Await.result(storage.close(), 10.seconds)
    Robolectric.application.getDatabasePath(storage.dbHelper.getDatabaseName).delete()
  }

  def addLocalMessage(msg: MessageData): MessageData = Await.result(zms.messagesContent.addLocalMessage(msg), 5.seconds)

  def getMessage(id: MessageId) = Await.result(zms.messagesStorage.getMessage(id), 5.seconds)

  feature("Post text message") {
    def addMessage(): MessageData = addLocalMessage(MessageData(MessageId(), conv.id, Message.Type.TEXT, userId, MessageData.textContent("text"), time = Instant.now))

    scenario("Post text successfully") {
      val msg = addMessage()

      postMessageResponse = Future.successful(Right(new Date))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Success
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.SENT)
    }

    scenario("Text message fails due to server error") {
      val msg = addMessage()
      postMessageResponse = Future.successful(Left(ErrorResponse(500, "", "")))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = true)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.PENDING)
    }

    scenario("Text message fails due to server error when offline") {
      zms.network.networkMode ! NetworkMode.OFFLINE

      val msg = addMessage()
      postMessageResponse = Future.successful(Left(ErrorResponse(500, "", "")))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }

    scenario("Text message fails due to client error") {
      val msg = addMessage()
      postMessageResponse = Future.successful(Left(ErrorResponse(400, "", "")))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(400, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }

    scenario("Text message fails due to server error after timeout") {
      val msg = addMessage()
      Await.result(zms.messagesContent.updateMessage(msg.id) { _.copy(time = now - sendingTimeout - 10.millis) }, 5.seconds)
      postMessageResponse = Future.successful(Left(ErrorResponse(500, "", "")))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }

    scenario("Message sending is cancelled by network client") {
      val msg = addMessage()
      postMessageResponse = CancellableFuture.delayed(2.seconds) { Left(ErrorResponse.Cancelled) }

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 5.seconds) shouldEqual SyncResult.Failure(Some(ErrorResponse.Cancelled), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED_READ)
    }
  }


  feature("Post image message") {

    def addMessage() = {
      val asset: AssetData = AssetData(metaData = Some(Image(Dim2(100, 100), Medium)), mime = Mime("image/jpg"), remoteId = Some(RAssetId()))
      val updated: AssetData = Await.result(zms.assetsStorage.insert(asset), 15.seconds)
      val id = MessageId(updated.id.str)
      val msg = addLocalMessage(MessageData(id, conv.id, Message.Type.ASSET, userId, protos = Seq(GenericMessage(id.uid, Asset(updated))), time = Instant.now))

      (msg, updated)
    }

    scenario("Post image successfully") {
      val (msg, asset) = addMessage()

      postImageResult = Right(None)

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Success
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.SENT)
    }

    scenario("Post image fails completely due to server error") {
      val (msg, asset) = addMessage()

      postImageResult = Left(ErrorResponse(500, "", ""))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = true)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.PENDING)
    }

    scenario("Post image fails completely due to server error when offline") {
      zms.network.networkMode ! NetworkMode.OFFLINE
      val (msg, _) = addMessage()
      postImageResult = Left(ErrorResponse(500, "", ""))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }

    scenario("Post image fails completely due to client error") {
      val (msg, asset) = addMessage()

      postImageResult = Left(ErrorResponse(400, "", ""))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(400, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }

    scenario("Post image fails completely due to server error after timeout") {
      val (msg, asset) = addMessage()
      zms.messagesContent.updateMessage(msg.id) { _.copy(time = now - sendingTimeout - 1.milli) } .await()

      postImageResult = Left(ErrorResponse(500, "", ""))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }

    scenario("Post image fails partially due to server error after timeout") {
      val (msg, asset) = addMessage()
      addLocalMessage(msg.copy(localTime = now - sendingTimeout - 1.milli))

      postImageResult = Left(ErrorResponse(500, "", ""))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(500, "", "")), shouldRetry = true)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.PENDING)
    }

    scenario("Post image fails partially due to client error after timeout") {
      val (msg, asset) = addMessage()
      addLocalMessage(msg.copy(localTime = now - sendingTimeout - 1.milli))

      postImageResult = Left(ErrorResponse(400, "", ""))

      Await.result(handler.postMessage(conv.id, msg.id, Instant.EPOCH), 1.second) shouldEqual SyncResult.Failure(Some(ErrorResponse(400, "", "")), shouldRetry = false)
      getMessage(msg.id).map(_.state) shouldEqual Some(Message.Status.FAILED)
    }
  }
}
