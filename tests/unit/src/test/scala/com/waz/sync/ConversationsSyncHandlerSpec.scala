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

import com.waz._
import com.waz.api.ErrorType
import com.waz.api.impl.ErrorResponse
import com.waz.model.ConversationData.ConversationType
import com.waz.model.ErrorData.ErrorDataDao
import com.waz.model._
import com.waz.service.conversation.ConversationsService
import com.waz.sync.client.ConversationsClient.ConversationResponse
import com.waz.sync.client.ConversationsClient.ConversationResponse.ConversationsResult
import com.waz.sync.client._
import com.waz.testutils._
import com.waz.testutils.Matchers._
import com.waz.testutils.{DefaultPatienceConfig, MockZMessaging, _}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.wrappers.DB
import com.waz.znet.ZNetClient._
import org.scalatest._
import org.scalatest.concurrent.{ScalaFutures, ScaledTimeSpans}
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

@Ignore class ConversationsSyncHandlerSpec extends FeatureSpec with Matchers with ScalaFutures with ScaledTimeSpans with BeforeAndAfter with GivenWhenThen with RobolectricTests with RobolectricUtils with DefaultPatienceConfig { test =>

  type EventsGenerator = (RConvId, Option[Long], Option[Long], Option[Int]) => Option[Seq[ConversationEvent]]
  type ConvsGenerator = (Option[RConvId]) => Either[ErrorResponse, ConversationsResult]
  
  private lazy implicit val dispatcher = Threading.Background

  implicit def db: DB = service.db.dbHelper.getWritableDatabase

  var convsRequest = List[Option[RConvId]]()
  var postConvRequests = Seq.empty[Seq[UserId]]
  var postJoinRequests = Seq.empty[(RConvId, Seq[UserId])]

  var loadDelay = 0.seconds
  var convsResponse: ConvsGenerator = _
  var postConvResponse: Either[ErrorResponse, ConversationResponse] = _
  var postMemberJoinResponse: Either[ErrorResponse, Option[MemberJoinEvent]] = _
  var postMemberLeaveResponse: Either[ErrorResponse, Option[MemberLeaveEvent]] = _

  def handler = service.conversationSync

  lazy val service = new MockZMessaging() {

    lifecycle.acquireUi()

    override lazy val conversations: ConversationsService =
      new ConversationsService(context, selfUserId, pushSignals, users, usersStorage, membersStorage,
        convsStorage, convsContent, sync, errors, messages, messagesContent, userPrefs, eventScheduler) {

        override def updateConversations(conversations: Seq[ConversationResponse]) = Future.successful(Nil)
      }

    override lazy val convClient = new ConversationsClient(zNetClient) {

      override def loadConversations(start: Option[RConvId], limit: Int): ErrorOrResponse[ConversationsResult] = {
        convsRequest ++= List(start)
        CancellableFuture.delayed(loadDelay)(test.convsResponse(start))
      }

      override def postConversation(users: Seq[UserId], name: Option[String], team: Option[TeamId]): ErrorOrResponse[ConversationResponse] = {
        postConvRequests :+= users
        CancellableFuture.successful(postConvResponse)
      }
      override def postMemberJoin(conv: RConvId, members: Seq[UserId]): ErrorOrResponse[Option[MemberJoinEvent]] = {
        postJoinRequests :+= (conv, members)
        CancellableFuture.successful(postMemberJoinResponse)
      }
      override def postMemberLeave(conv: RConvId, member: UserId): ErrorOrResponse[Option[MemberLeaveEvent]] = CancellableFuture.successful(postMemberLeaveResponse)
    }
  }

  before {
    convsRequest = Nil
    postConvRequests = Nil
    convsResponse = { _ => Right(ConversationsResult(Nil, hasMore = false)) }
    postConvResponse = Left(ErrorResponse(500, "", ""))
    postMemberJoinResponse = Left(ErrorResponse(500, "", ""))
    postConvRequests = Seq.empty
    postJoinRequests = Seq.empty
  }

  after {
    service.errors.errorsStorage.deleteAll().await()
    service.convsContent.storage.deleteAll().await()
  }

  feature("Sync conversations") {

    scenario("conversations paging") {
      loadDelay = 200.millis
      convsResponse = {
        case None => Right(ConversationsResult((1 to 100) map (i => ConversationResponse(ConversationData(ConvId(i.toString), RConvId(i.toString), None, UserId(), ConversationType.Group), Nil)), hasMore = true))
        case Some(RConvId("100")) => Right(ConversationsResult((101 to 200) map (i => ConversationResponse(ConversationData(ConvId(i.toString), RConvId(i.toString), None, UserId(), ConversationType.Group), Nil)), hasMore = true))
        case _ => Right(ConversationsResult((201 to 220) map (i => ConversationResponse(ConversationData(ConvId(i.toString), RConvId(i.toString), None, UserId(), ConversationType.Group), Nil)), hasMore = false))
      }
      Await.result(handler.syncConversations(), 1.second) shouldEqual SyncResult.Success
      convsRequest shouldEqual List(None, Some(RConvId(100.toString)), Some(RConvId(200.toString)))
    }

    scenario("create conv with too many members") {
      val conv = insertConv(ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group, lastEventTime = Instant.ofEpochMilli(1500)))
      val members = Seq.fill(128)(UserId())
      postConvResponse = Right(ConversationResponse(conv, members.take(64).map(u => ConversationMemberData(u, conv.id))))
      postMemberJoinResponse = Right(Some(MemberJoinEvent(conv.remoteId, new Date, UserId(), members.drop(64), false)))

      handler.postConversation(conv.id, members, Some("Test group"), None).futureValue shouldEqual SyncResult.Success

      postConvRequests shouldEqual Seq(members.take(64))
      postJoinRequests shouldEqual Seq((conv.remoteId, members.drop(64)))
    }
  }

  feature("Error reporting") {

    scenario("create group conversation with unconnected user") {
//      service.lifecycle.setLoggedIn(true)
      val conv = insertConv(ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group))
      val user1 = UserId()
      val user2 = UserId()
      service.membersStorage.add(conv.id, user1, user2).futureValue
      postConvResponse = Left(ErrorResponse(403, "", "not-connected"))
      Await.result(handler.postConversation(conv.id, Seq(user1, user2), None, None), 1.second) shouldEqual SyncResult.Failure(Some(postConvResponse.left.get), shouldRetry = false)
      postConvRequests should not be empty
      ErrorDataDao.list should have size 1
      val err = ErrorDataDao.list.head
      err.errType shouldEqual ErrorType.CANNOT_CREATE_GROUP_CONVERSATION_WITH_UNCONNECTED_USER
      err.convId shouldEqual Some(conv.id)
      service.convsStorage.get(conv.id) should eventually(be('defined))
    }

    scenario("add unconnected user to conversation") {
      val conv = insertConv(ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group))
      val user1 = UserId()
      val user2 = UserId()
      service.membersStorage.add(conv.id, user1, user2).futureValue
      postMemberJoinResponse = Left(ErrorResponse(403, "", "not-connected"))
      Await.result(handler.postConversationMemberJoin(conv.id, Seq(user1, user2)), 1.second) shouldEqual SyncResult.Failure(Some(postMemberJoinResponse.left.get), shouldRetry = false)
      service.convsStorage.get(conv.id) should eventually(be('defined))
      service.membersStorage.getActiveUsers(conv.id).futureValue shouldBe empty
      ErrorDataDao.list should have size 1
      val err = ErrorDataDao.list.head
      err.errType shouldEqual ErrorType.CANNOT_ADD_UNCONNECTED_USER_TO_CONVERSATION
      err.convId shouldEqual Some(conv.id)
      err.users shouldEqual Seq(user1, user2)
    }
  }

  feature("Sync members") {

    scenario("Joining member was already in the conversation") {
      val conv = insertConv(ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group))
      val user1 = UserId()
      val user2 = UserId()
      val user3 = UserId()
      service.membersStorage.add(conv.id, user1, user2).futureValue
      postMemberJoinResponse = Right(None)
      handler.postConversationMemberJoin(conv.id, Seq(user3)) should eventually(be(SyncResult.Success))
    }

    scenario("Leaving member was already removed") {
      val conv = insertConv(ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group))
      val user1 = UserId()
      val user2 = UserId()
      service.membersStorage.add(conv.id, user1, user2).futureValue
      postMemberLeaveResponse = Right(None)
      handler.postConversationMemberLeave(conv.id, user2) should eventually(be(SyncResult.Success))
    }

    scenario("member status changed") {
      pending
    }

    scenario("added members") {
      pending
    }

    scenario("Post many members") {
      val conv = insertConv(ConversationData(ConvId(), RConvId(), None, UserId(), ConversationType.Group))
      val members = Seq.fill(128)(UserId())
      postMemberJoinResponse = Right(Some(MemberJoinEvent(conv.remoteId, new Date, UserId(), members.take(64), false)))

      handler.postConversationMemberJoin(conv.id, members).futureValue shouldEqual SyncResult.Success

      postJoinRequests.toSet shouldEqual Set((conv.remoteId, members.take(64)), (conv.remoteId, members.drop(64)))
    }
  }

  def insertConv(data: ConversationData): ConversationData = Await.result(service.convsStorage.insert(data), 5.seconds)

  def generateEvents(maxSeq: Long)(convId: RConvId, start: Option[Long], end: Option[Long], size: Option[Int]) = {
    val startSeq = start.fold(1L)(_ max 1)
    val endSeq = maxSeq min end.getOrElse(maxSeq) min size.fold(maxSeq)(_ + startSeq - 1)
    info(s"generateEvents($start, $end, $size) - generating from: $startSeq to: $endSeq")
    Some(for (i <- startSeq to endSeq) yield textMessageEvent(Uid(i, i), convId, new Date(i * 1000), UserId(i.toString), i.toString)) // generated messages have to be consistent (expecially uid and userId)
  }
}
