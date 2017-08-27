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

import akka.pattern.ask
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.IConversation.Type.GROUP
import com.waz.api._
import com.waz.model.Liking.Action._
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.provision.ActorMessage.{AwaitSyncCompleted, Login, SetMessageReaction, Successful}
import com.waz.service.{UserModule, ZMessaging, ZMessagingFactory}
import com.waz.sync.client.MessagesClient
import com.waz.sync.client.MessagesClient.OtrMessage
import com.waz.sync.client.OtrClient.MessageResponse
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import com.waz.testutils.TestApplication.notificationsSpy
import com.waz.threading.CancellableFuture.delay
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.znet.ZNetClient._
import org.scalatest._
import org.scalatest.matchers.Matcher

import scala.concurrent.Promise
import scala.concurrent.duration._

class MessageReactionsSpec extends FeatureSpec with Matchers with BeforeAndAfterAll with BeforeAndAfter with OptionValues with Inspectors with ProvisionedApiSpec with ThreadActorSpec { test =>

  scenario("Initial sync") {
    (msgs should have size 1).soon
    (groupMsgs should have size 1).soon
  }

  scenario("Init other client") {
    otherUserClient ? Login(provisionedEmail("auto2"), "auto2_pass") should eventually(be(Successful))
    otherUserClient ? AwaitSyncCompleted should eventually(be(Successful))
  }

  scenario("This and other user like and unlike a message") {
    val (n, message) = addMessage("meep", conv)

    (message should (not(beLiked) and not(beLikedByThisUser) and notHaveLikes)).soon

    otherUserDoes(Like, message, conv)
    (message should (beLiked and not(beLikedByThisUser) and haveLikesFrom(otherUser))).soon

    message.like()
    (message should (beLiked and beLikedByThisUser and haveLikesFrom(otherUser, self))).soon

    otherUserDoes(Unlike, message, conv)
    (message should (beLiked and beLikedByThisUser and haveLikesFrom(self))).soon

    otherUserDoes(Like, message, conv)
    (message should (beLiked and beLikedByThisUser and haveLikesFrom(self, otherUser))).soon

    message.unlike()
    (message should (beLiked and not(beLikedByThisUser) and haveLikesFrom(otherUser))).soon

    message.like()
    (message should (beLiked and beLikedByThisUser and haveLikesFrom(otherUser, self))).soon

    message.unlike()
    (message should (beLiked and not(beLikedByThisUser) and haveLikesFrom(otherUser))).soon

    otherUserDoes(Unlike, message, conv)
    (message should (not(beLiked) and not(beLikedByThisUser) and notHaveLikes)).soon
  }

  scenario("User A receives a notification if user B likes a message (written by A)") {
    forAll(Seq(conv, group)) { c =>
      val (n, message) = addMessage("moop", c)

      (message should (not(beLiked) and not(beLikedByThisUser) and notHaveLikes)).soon

      try {
        api.onPause()
//        (zmessaging.lifecycle.isUiActive shouldBe false).soon

        notificationsSpy.gcms = Nil
        otherUserDoes(Like, message, c)
        (notificationsSpy.gcms should have size 1).soon
        (notificationsSpy.gcms.head should have size 1).soon
        notificationsSpy.gcms.head.head.convId shouldEqual c.data.id

        otherUserDoes(Unlike, message, c)
        (notificationsSpy.gcms should have size 2).soon
        (notificationsSpy.gcms(1) shouldBe empty).soon
      } finally {
        api.onResume()
//        (zmessaging.lifecycle.isUiActive shouldBe true).soon
      }
    }
  }

  scenario("Liked message is edited") {
    val (n, message) = addMessage("whee", conv)

    (message should (not(beLiked) and not(beLikedByThisUser) and notHaveLikes)).soon
    otherUserDoes(Like, message, conv)
    (message should (beLiked and not(beLikedByThisUser) and haveLikesFrom(otherUser))).soon

    message.update("woo")
    soon {
      val editedMessage = msgs(n)
      editedMessage should (not(beLiked) and not(beLikedByThisUser) and notHaveLikes)
    }
  }

  scenario("Rapidly changing one's reaction") {
    val (n, message) = addMessage("amazeballs", conv)
    val gate = Promise[Unit]()
    Serialized('PostReaction)(gate.future.lift)
    delayMessages = true

    3.times {
      debug("like")
      msgs(n).like()
      (msgs(n) should beLikedByThisUser).soon
      debug("unlike")
      msgs(n).unlike()
      (msgs(n) should not(beLikedByThisUser)).soon
    }
    debug("last like")
    msgs(n).like()
    (msgs(n) should beLikedByThisUser).soon
    gate.success(())
  }

  before {
    delayMessages = false
  }

  override val provisionFile = "/three_users_group_conv.json"

  lazy val conversations = api.getConversations
  lazy val self = provisionedUserId("auto1")
  lazy val otherUser = provisionedUserId("auto2")
  lazy val conv = conversations.find(_.getId == otherUser.str).value
  lazy val group = conversations.find(_.getType == GROUP).value
  def msgs = listMessages(conv.id)
  def groupMsgs = listMessages(group.id)

  lazy val otherUserClient = registerDevice(logTagFor[MessageReactionsSpec])

  def addMessage(text: String, c: IConversation): (Int, MessageData) = {
    def m = listMessages(c.id)
    val n = m.size
    zmessaging.convsUi.sendMessage(conv.id, text)
    (m should have size (n + 1)).soon
    (n, m(n))
  }

  def otherUserDoes(action: Liking.Action, msg: MessageData, c: IConversation) = otherUserClient ? SetMessageReaction(c.data.remoteId, msg.id, action) should eventually(be(Successful))

  def getLikers(m: MessageData) = getLikes(m.id).likers.toSeq.sortBy(_._2.toEpochMilli).map(_._1)

  def beLiked: Matcher[MessageData] = not(be(empty)).matcher[Seq[UserId]] compose getLikers
  def beLikedByThisUser = contain(zmessaging.selfUserId).matcher[Seq[UserId]] compose getLikers
  def notHaveLikes: Matcher[MessageData] = be(empty).matcher[Seq[UserId]] compose getLikers
  def haveLikesFrom(ids: UserId*): Matcher[MessageData] = contain.theSameElementsInOrderAs(ids).matcher[Seq[UserId]] compose getLikers

  @volatile var delayMessages = false

  override lazy val zmessagingFactory: ZMessagingFactory = new ZMessagingFactory(globalModule) {
    override def zmessaging(teamId: Option[TeamId], clientId: ClientId, user: UserModule): ZMessaging = new ApiZMessaging(teamId, clientId, user) {
      override lazy val messagesClient = new MessagesClient(netClient) {
        override def postMessage(conv: RConvId, content: OtrMessage, ignoreMissing: Boolean, receivers: Option[Set[UserId]] = None): ErrorOrResponse[MessageResponse] =
          if (! delayMessages) super.postMessage(conv, content, ignoreMissing, receivers)
          else Serialized('PostReaction)(delay(555.millis).flatMap(_ => super.postMessage(conv, content, ignoreMissing, receivers))(Threading.Background))
      }
    }
  }
}
