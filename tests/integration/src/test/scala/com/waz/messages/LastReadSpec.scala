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

import java.util.Date

import akka.pattern.ask
import com.waz.api._
import com.waz.api.impl.ErrorResponse
import com.waz.model.GenericContent.LastRead
import com.waz.model.otr.ClientId
import com.waz.model._
import com.waz.provision.ActorMessage.{AwaitSyncCompleted, Login, SendText, Successful}
import com.waz.service._
import com.waz.sync.otr.{OtrSyncHandler, OtrSyncHandlerImpl}
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FeatureSpec, Matchers}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

class LastReadSpec extends FeatureSpec with Matchers with BeforeAndAfterAll with BeforeAndAfter with ProvisionedApiSpec with ThreadActorSpec { test =>

  override val provisionFile = "/two_users_connected.json"

  lazy val conversations = api.getConversations
  lazy val self = provisionedUserId("auto1")
  lazy val otherUser = provisionedUserId("auto2")
  lazy val conv = conversations.get(0)
  def msgs = listMessages(conv.id)
  lazy val remoteConvId = conv.data.remoteId

  lazy val otherUserClient = registerDevice("other_user")
  lazy val secondClient = registerDevice("second_client")

  var postLastReadRequests = Seq.empty[(RConvId, Instant)]

  override lazy val timeouts: Timeouts = new Timeouts {
    override val messages: Messages = new Messages {
      override def lastReadPostDelay: Timeout = 2.seconds
    }
  }

  override lazy val zmessagingFactory: ZMessagingFactory = new ZMessagingFactory(globalModule) {
    override def zmessaging(teamId: Option[TeamId], clientId: ClientId, userModule: UserModule): ZMessaging = new ApiZMessaging(teamId, clientId, userModule) {


      override lazy val otrSync: OtrSyncHandler = new OtrSyncHandlerImpl(otrClient, messagesClient, assetClient, otrService, assets, conversations, convsStorage, users, messages, errors, otrClientsSync, cache, pushSignals) {
        override def postOtrMessage(convId: ConvId, remoteId: RConvId, message: GenericMessage, recipients: Option[Set[UserId]], nativePush: Boolean = true): Future[Either[ErrorResponse, Date]] = {
          if (convId.str == self.str)
            message match {
              case GenericMessage(_, LastRead(cId, time)) =>
                postLastReadRequests = postLastReadRequests :+ (cId, time)
            }
          super.postOtrMessage(convId, remoteId, message)
        }
      }
    }
  }

  before {
    postLastReadRequests = Nil
  }

  scenario("Init remotes") {
    secondClient ? Login(email, password) should eventually(be(Successful))
    secondClient ? AwaitSyncCompleted should eventually(be(Successful))

    otherUserClient ? Login(provisionedEmail("auto2"), "auto2_pass") should eventually(be(Successful))
    otherUserClient ? AwaitSyncCompleted should eventually(be(Successful))
  }

  scenario("Receive messages from other user") {
    withDelay {
      conversations should not be empty
      msgs should not be empty
    }

    zmessaging.messages.markMessageRead(conv.id, msgs(0).id)

    for (i <- 0 until 5) {
      otherUserClient ? SendText(conv.data.remoteId, s"test message $i") should eventually(be(Successful))
    }

    withDelay {
      msgs should have size 6
      conv.getUnreadCount shouldEqual 5

      postLastReadRequests shouldBe empty
    }
  }

  scenario("Receive message sent from second client and update lastRead for it") {
    val fromBefore = msgs.size

    secondClient ? SendText(conv.data.remoteId, s"message from self") should eventually(be(Successful))

    withDelay {
      msgs should have size (fromBefore + 1)
      conv.getUnreadCount shouldEqual 0
    }
    postLastReadRequests shouldBe empty
  }

  scenario("send couple messages, don't send lastRead") {
    val fromBefore = msgs.size

    for (i <- 0 until 5) {
      zmessaging.convsUi.sendMessage(conv.id, s"own mesage: $i")
    }

    withDelay {
      msgs should have size (fromBefore + 5)
      msgs.last.state shouldEqual Message.Status.SENT
      conv.getUnreadCount shouldEqual 0
    }

    awaitUi(3.seconds)
    postLastReadRequests shouldBe empty
  }

  scenario("receive couple messages from other user") {
    val fromBefore = msgs.size
    for (i <- 0 until 5) {
      otherUserClient ? SendText(conv.data.remoteId, s"test message $i") should eventually(be(Successful))
    }

    withDelay {
      msgs should have size (fromBefore + 5)
      conv.getUnreadCount shouldEqual 5

      postLastReadRequests shouldBe empty
    }
  }

  scenario("read all messages, send one LastRead msg") {
    val fromBefore = msgs.size
    for (i <- fromBefore - 5 until fromBefore) {
      zmessaging.messages.markMessageRead(conv.id, msgs(i).id)
    }

    withDelay {
      conv.getUnreadCount shouldEqual 0
    }
    awaitUi(3.seconds)
    postLastReadRequests should beMatching({ case Seq((`remoteConvId`, _)) => true }) // only one request
  }

}
