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
package com.waz.service.conversation

import java.util.Date

import android.graphics.BitmapFactory
import com.waz._
import com.waz.api.Message.Status
import com.waz.api.MessageContent.Image
import com.waz.api.{Message, MessageContent}
import com.waz.content.{AssetsStorage, ConversationStorage, MembersStorage, UsersStorage}
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.Text
import com.waz.model._
import com.waz.service._
import com.waz.service.assets.AssetService
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.testutils.Matchers._
import com.waz.testutils._
import com.waz.threading.Threading
import com.waz.utils.IoUtils.{toByteArray, withResource}
import com.waz.utils._
import com.waz.utils.events.EventContext
import com.waz.utils.wrappers.DB
import org.robolectric.shadows.ShadowLog
import org.scalatest.BeforeAndAfter
import org.threeten.bp.Instant

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random
import scala.collection.mutable

class MessageSendingSpec extends AndroidFreeSpec { test =>
  implicit lazy val dispatcher = Threading.Background

  val accountId = AccountId()
  lazy val selfUser = UserData("self user")
  lazy val conv = ConversationData(ConvId(), RConvId(), Some("convName"), selfUser.id, ConversationType.Group)
  
  private def stubService(
                           assets:          AssetService                 = stub[AssetService],
                           users:           UserService                  = stub[UserService],
                           usersStorage:    UsersStorage                 = stub[UsersStorage],
                           messages:        MessagesService              = stub[MessagesService],
                           messagesContent: MessagesContentUpdater       = null, //stub[MessagesContentUpdater],
                           members:         MembersStorage               = stub[MembersStorage],
                           assetStorage:    AssetsStorage                = null, //stub[AssetsStorage],
                           convsContent:    ConversationsContentUpdater  = stub[ConversationsContentUpdater],
                           convStorage:     ConversationStorage          = stub[ConversationStorage],
                           network:         NetworkModeService           = stub[NetworkModeService],
                           convs:           ConversationsService         = null, //stub[ConversationsService],
                           sync:            SyncServiceHandle            = stub[SyncServiceHandle],
                           lifecycle:       ZmsLifeCycle                 = stub[ZmsLifeCycle],
                           errors:          ErrorsService                = null //stub[ErrorsService]
  ) = new ConversationsUiServiceImpl(accountId,
    UserId(), assets, users, usersStorage, messages, messagesContent, members,
    assetStorage, convsContent, convStorage, network, convs, sync, lifecycle, errors
  )

  feature("Text messages") {
    scenario("Add text message") {
      val messages = mock[MessagesService]
      val mId = MessageId()
      val msgData = MessageData(mId, conv.id, Message.Type.TEXT, UserId(), MessageData.textContent("test"), protos = Seq(GenericMessage(mId.uid, Text("test", Map.empty, Nil))))
      val syncId = SyncId()

      (messages.addTextMessage _).expects(conv.id, "test", Map.empty[UserId, String]).once().returning(Future.successful(msgData))

      val convsContent = mock[ConversationsContentUpdater]
      (convsContent.convById _).expects(conv.id).once().returning(Future.successful(Some(conv)))
      (convsContent.updateConversationLastRead _).expects(conv.id, msgData.time).once().returning(Future.successful(Some((conv, conv))))

      val sync = mock[SyncServiceHandle]
      (sync.postMessage _).expects(msgData.id, conv.id, msgData.editTime).once().returning(Future.successful(syncId))
      val convsUi = stubService(messages = messages, convsContent = convsContent, sync = sync)

      val msg = Await.result(convsUi.sendMessage(conv.id, "test"), 1.second).get
      msg.contentString shouldEqual "test"
    }
  }
/*
  feature("Image messages") {
    def imageStream = getClass.getResourceAsStream("/images/penguin.png")

    scenario("send image and access local message") {
      val bitmap = withResource(imageStream)(BitmapFactory.decodeStream)
      info(s"bitmap size: (${bitmap.getWidth}, ${bitmap.getHeight})")

      val msg = sendMessage(new Image(ui.images.createImageAssetFrom(toByteArray(imageStream))))
      val asset = Await.result(service.assetsStorage.get(msg.assetId), 5.seconds).get

      asset.width shouldEqual bitmap.getWidth
      asset.height shouldEqual bitmap.getHeight

      Await.result(service.imageLoader.loadRawImageData(asset), 5.seconds) should be('defined)

      val entry = service.lastMessage(conv.id)
      entry.map(_.id) shouldEqual Some(msg.id)
      entry.map(_.msgType) shouldEqual Some(Message.Type.ASSET)
      entry.flatMap(_.imageDimensions) shouldEqual Some(Dim2(asset.width, asset.height))
    }
  }

  feature("Ordering") {
    import testutils.withUpdate

    scenario("Don't update messages list if time is unchanged") {
      val msgs = service.messagesStorage.getEntries(conv.id)
      @volatile var updateCount = 0
      msgs { _ => updateCount += 1 } (EventContext.Global)

      val msg = withUpdate(msgs) {
        sendMessage(new MessageContent.Text("test"))
      }
      updateCount = 0

      service.dispatchEvent(textMessageEvent(Uid(msg.id.str), conv.remoteId, msg.time.javaDate, selfUser.id, "test"))
      Thread.sleep(200L)
      val e = getMessage(msg.id).get
      e.state shouldEqual Message.Status.SENT

      updateCount shouldEqual 0
    }

    def reload(msg: MessageData) = service.messagesStorage.get(msg.id).await().get

    scenario("Send multiple messages and maintain local ordering") {
      val msgs = service.messagesStorage.getEntries(conv.id)
      val msg = withUpdate(msgs) { sendMessage(new MessageContent.Text("test")) }
      val msg1 = withUpdate(msgs) { sendMessage(new MessageContent.Text("test1")) }
      withUpdate(msgs) { sendMessage(new MessageContent.Text("test2")) }
      withUpdate(msgs) { sendMessage(new MessageContent.Text("test3")) }

      val time = Instant.now()
      withUpdate(msgs) {
        service.messagesSync.messageSent(conv.id, msg, time + 1.second)
      }

      val ms = listMessages
      ms.drop(ms.size - 4).map(_.contentString) shouldEqual Seq("test", "test1", "test2", "test3")

      withUpdate(msgs) {
        service.messagesSync.messageSent(conv.id, reload(msg1), time + 2.seconds)
      }

      val ms1 = listMessages
      withClue(ms1.map(m => (m.contentString, m.time))) {
        ms1.drop(ms1.size - 4).map(_.contentString) shouldEqual Seq("test", "test1", "test2", "test3")
      }
    }

    scenario("Send multiple messages and maintain local ordering when local clock is in future") {
      val time = Instant.now().minus(1.minute)

      val msgs = service.messagesStorage.getEntries(conv.id)
      val msg = withUpdate(msgs) { sendMessage(new MessageContent.Text("test")) }
      val msg1 = withUpdate(msgs) { sendMessage(new MessageContent.Text("test1")) }
      withUpdate(msgs) { sendMessage(new MessageContent.Text("test2")) }
      withUpdate(msgs) { sendMessage(new MessageContent.Text("test3")) }

      withUpdate(msgs) {
        service.messagesSync.messageSent(conv.id, msg, time + 1.second)
      }

      val ms = listMessages
      ms.drop(ms.size - 4).map(_.contentString) shouldEqual Seq("test", "test1", "test2", "test3")

      info(ms.drop(ms.size - 5).map(m => (m.contentString, m.time)).mkString(", "))

      withUpdate(msgs) {
        service.messagesSync.messageSent(conv.id, reload(msg1), time + 2.seconds)
      }

      val ms1 = listMessages
      withClue(ms1.map(m => (m.contentString, m.time))) {
        ms1.drop(ms1.size - 4).map(_.contentString) shouldEqual Seq("test", "test1", "test2", "test3")
      }
    }
  }

  feature("Last read") {
    scenario("Sent message should be marked as read") {
      service.dispatchEvent(textMessageEvent(Uid(), conv.remoteId, new Date(), selfUser.id, "test 1"))
      service.dispatchEvent(textMessageEvent(Uid(), conv.remoteId, new Date(), selfUser.id, "test 2"))
      Thread.sleep(100L)

      val msg = sendMessage(new MessageContent.Text("test"))
      msg.contentString shouldEqual "test"
      messageSync shouldEqual Some(msg.id)
      msg.state shouldEqual Message.Status.PENDING
      Await.result(service.convsStorage.get(conv.id), 1.second).map(_.lastRead) shouldEqual Some(msg.time)
    }

    scenario("Mark posted message read once it's synced with same id sequence") {
      service.dispatchEvent(textMessageEvent(Uid(), conv.remoteId, new Date(), selfUser.id, "test 1"))
      service.dispatchEvent(textMessageEvent(Uid(), conv.remoteId, new Date(), selfUser.id, "test 2"))
      Thread.sleep(100L)

      val msg = sendMessage(new MessageContent.Text("test"))
      Await.result(service.convsStorage.get(conv.id), 1.second).map(_.lastRead) shouldEqual Some(msg.time)

      lastReadSync shouldEqual None
      val time = new Date()
      withEvent(service.messagesStorage.messageChanged) { case _ => true } {
        service.convOrder.handlePostConversationEvent(textMessageEvent(Uid(msg.id.str), conv.remoteId, time, selfUser.id, "test"))
      }
      getMessage(msg.id).get.state shouldEqual Message.Status.SENT
    }

    scenario("Change last read even when unreadCount > 0") {
      service.dispatchEvent(textMessageEvent(Uid(), conv.remoteId, new Date(), selfUser.id, "test 1"))
      service.dispatchEvent(textMessageEvent(Uid(), conv.remoteId, new Date(), selfUser.id, "test 2"))
      Thread.sleep(100L)

      val msg = sendMessage(new MessageContent.Text("test"))
      Await.result(service.convsStorage.update(conv.id, _.copy(unreadCount = 1)), 1.second)

      val time = new Date
      withEvent(service.messagesStorage.messageChanged) { case _ => true } {
        service.convOrder.handlePostConversationEvent(textMessageEvent(Uid(msg.id.str), conv.remoteId, time, selfUser.id, "test"))
      }
      getMessage(msg.id).get.state shouldEqual Message.Status.SENT
      Await.result(service.convsStorage.get(conv.id), 1.second).map(_.lastRead) shouldEqual Some(time.instant)
      lastReadSync shouldEqual None
    }
  }*/
}
