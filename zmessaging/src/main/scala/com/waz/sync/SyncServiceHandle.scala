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

import com.waz.api.EphemeralExpiration
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.model.sync.SyncJob.Priority
import com.waz.model.sync._
import com.waz.service._
import com.waz.sync.otr.OtrClientsSyncHandler
import com.waz.sync.queue.ConvLock
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

trait SyncServiceHandle {
  def syncUsersIfNotEmpty(ids: Seq[UserId]): Future[Unit] = if (ids.nonEmpty) syncUsers(ids: _*).map(_ => ())(Threading.Background) else Future.successful(())

  def syncSearchQuery(query: SearchQuery): Future[SyncId]
  def exactMatchHandle(handle: Handle): Future[SyncId]
  def syncUsers(ids: UserId*): Future[SyncId]
  def syncSelfUser(): Future[SyncId]
  def deleteAccount(): Future[SyncId]
  def syncConversations(ids: Set[ConvId] = Set.empty, dependsOn: Option[SyncId] = None): Future[SyncId]
  def syncTeam(dependsOn: Option[SyncId] = None): Future[SyncId]
  def syncTeamMember(id: UserId): Future[SyncId]
  def syncConnectedUsers(): Future[SyncId]
  def syncConnections(dependsOn: Option[SyncId] = None): Future[SyncId]
  def syncRichMedia(id: MessageId, priority: Int = Priority.MinPriority): Future[SyncId]

  def postSelfUser(info: UserInfo): Future[SyncId]
  def postSelfPicture(picture: Option[AssetId]): Future[SyncId]
  def postMessage(id: MessageId, conv: ConvId, editTime: Instant): Future[SyncId]
  def postDeleted(conv: ConvId, msg: MessageId): Future[SyncId]
  def postRecalled(conv: ConvId, currentMsgId: MessageId, recalledMsgId: MessageId): Future[SyncId]
  def postAssetStatus(id: MessageId, conv: ConvId, exp: EphemeralExpiration, status: AssetStatus.Syncable): Future[SyncId]
  def postLiking(id: ConvId, liking: Liking): Future[SyncId]
  def postConnection(user: UserId, name: String, message: String): Future[SyncId]
  def postConnectionStatus(user: UserId, status: ConnectionStatus): Future[SyncId]
  def postConversationName(id: ConvId, name: String): Future[SyncId]
  def postConversationMemberJoin(id: ConvId, members: Seq[UserId]): Future[SyncId]
  def postConversationMemberLeave(id: ConvId, member: UserId): Future[SyncId]
  def postConversationState(id: ConvId, state: ConversationState): Future[SyncId]
  def postConversation(id: ConvId, users: Seq[UserId], name: Option[String], team: Option[TeamId]): Future[SyncId]
  def postLastRead(id: ConvId, time: Instant): Future[SyncId]
  def postCleared(id: ConvId, time: Instant): Future[SyncId]
  def postAddressBook(ab: AddressBook): Future[SyncId]
  def postInvitation(i: Invitation): Future[SyncId]
  def postTypingState(id: ConvId, typing: Boolean): Future[SyncId]
  def postOpenGraphData(conv: ConvId, msg: MessageId, editTime: Instant): Future[SyncId]
  def postReceipt(conv: ConvId, message: MessageId, user: UserId, tpe: ReceiptType): Future[SyncId]

  def registerPush(token: PushToken): Future[SyncId]
  def deletePushToken(token: PushToken): Future[SyncId]

  def syncSelfClients(): Future[SyncId]
  def postClientLabel(id: ClientId, label: String): Future[SyncId]
  def syncClients(user: UserId): Future[SyncId]
  def syncClientsLocation(): Future[SyncId]
  def syncPreKeys(user: UserId, clients: Set[ClientId]): Future[SyncId]
  def postSessionReset(conv: ConvId, user: UserId, client: ClientId): Future[SyncId]

  def postValidateHandles(handles: Seq[Handle]): Future[SyncId]
}

class AndroidSyncServiceHandle(service: => SyncRequestService, timeouts: Timeouts) extends SyncServiceHandle {

  import com.waz.model.sync.SyncRequest._

  private def addRequest(req: SyncRequest, priority: Int = Priority.Normal, dependsOn: Seq[SyncId] = Nil, optional: Boolean = false, timeout: Long = 0, forceRetry: Boolean = false, delay: FiniteDuration = Duration.Zero): Future[SyncId] = {
    val timestamp = SyncJob.timestamp
    val startTime = if (delay == Duration.Zero) 0 else timestamp + delay.toMillis
    service.addRequest(SyncJob(SyncId(), req, dependsOn.toSet, priority = priority, optional = optional, timeout = timeout, timestamp = timestamp, startTime = startTime), forceRetry)
  }

  def syncSearchQuery(query: SearchQuery) = addRequest(SyncSearchQuery(query), priority = Priority.High)
  def exactMatchHandle(handle: Handle) = addRequest(ExactMatchHandle(handle), priority = Priority.High)
  def syncUsers(ids: UserId*) = addRequest(SyncUser(ids.toSet))
  def syncSelfUser() = addRequest(SyncSelf, priority = Priority.High)
  def deleteAccount() = addRequest(DeleteAccount)
  def syncConversations(ids: Set[ConvId], dependsOn: Option[SyncId]) = addRequest(if (ids.nonEmpty) SyncConversation(ids) else SyncConversations, priority = if (ids.nonEmpty) Priority.Normal else Priority.High, dependsOn = dependsOn.toSeq)
  def syncTeam(dependsOn: Option[SyncId] = None): Future[SyncId] = addRequest(SyncTeam, priority = Priority.High, dependsOn = dependsOn.toSeq)
  def syncTeamMember(id: UserId): Future[SyncId] = addRequest(SyncTeamMember(id))
  def syncConnectedUsers() = addRequest(SyncConnectedUsers)
  def syncConnections(dependsOn: Option[SyncId]) = addRequest(SyncConnections, dependsOn = dependsOn.toSeq)
  def syncRichMedia(id: MessageId, priority: Int = Priority.MinPriority) = addRequest(SyncRichMedia(id), priority = priority)

  def postSelfUser(info: UserInfo) = addRequest(PostSelf(info))
  def postSelfPicture(picture: Option[AssetId]) = addRequest(PostSelfPicture(picture))
  def postMessage(id: MessageId, conv: ConvId, time: Instant) = addRequest(PostMessage(conv, id, time), timeout = System.currentTimeMillis() + timeouts.messages.sendingTimeout.toMillis, forceRetry = true)
  def postDeleted(conv: ConvId, msg: MessageId) = addRequest(PostDeleted(conv, msg))
  def postRecalled(conv: ConvId, msg: MessageId, recalled: MessageId) = addRequest(PostRecalled(conv, msg, recalled))
  def postAssetStatus(id: MessageId, conv: ConvId, exp: EphemeralExpiration, status: AssetStatus.Syncable) = addRequest(PostAssetStatus(conv, id, exp, status))
  def postAddressBook(ab: AddressBook) = addRequest(PostAddressBook(ab))
  def postInvitation(i: Invitation) = addRequest(PostInvitation(i))
  def postConnection(user: UserId, name: String, message: String) = addRequest(PostConnection(user, name, message))
  def postConnectionStatus(user: UserId, status: ConnectionStatus) = addRequest(PostConnectionStatus(user, Some(status)))
  def postTypingState(conv: ConvId, typing: Boolean) = addRequest(PostTypingState(conv, typing), optional = true, timeout = System.currentTimeMillis() + timeouts.typing.refreshDelay.toMillis)
  def postConversationName(id: ConvId, name: String) = addRequest(PostConvName(id, name))
  def postConversationState(id: ConvId, state: ConversationState) = addRequest(PostConvState(id, state))
  def postConversationMemberJoin(id: ConvId, members: Seq[UserId]) = addRequest(PostConvJoin(id, members.toSet))
  def postConversationMemberLeave(id: ConvId, member: UserId) = addRequest(PostConvLeave(id, member))
  def postConversation(id: ConvId, users: Seq[UserId], name: Option[String], team: Option[TeamId]) = addRequest(PostConv(id, users, name, team))
  def postLiking(id: ConvId, liking: Liking): Future[SyncId] = addRequest(PostLiking(id, liking))
  def postLastRead(id: ConvId, time: Instant) = addRequest(PostLastRead(id, time), priority = Priority.Low, delay = timeouts.messages.lastReadPostDelay)
  def postCleared(id: ConvId, time: Instant) = addRequest(PostCleared(id, time))
  def postOpenGraphData(conv: ConvId, msg: MessageId, time: Instant) = addRequest(PostOpenGraphMeta(conv, msg, time), priority = Priority.Low)
  def postReceipt(conv: ConvId, message: MessageId, user: UserId, tpe: ReceiptType): Future[SyncId] = addRequest(PostReceipt(conv, message, user, tpe), priority = Priority.Optional)

  def registerPush(token: PushToken) = addRequest(RegisterPushToken(token), priority = Priority.High, forceRetry = true)
  def deletePushToken(token: PushToken) = addRequest(DeletePushToken(token), priority = Priority.Low)

  def syncSelfClients() = addRequest(SyncSelfClients, priority = Priority.Critical)
  def postClientLabel(id: ClientId, label: String) = addRequest(PostClientLabel(id, label))
  def syncClients(user: UserId) = addRequest(SyncClients(user))
  def syncClientsLocation() = addRequest(SyncClientsLocation)
  def syncPreKeys(user: UserId, clients: Set[ClientId]) = addRequest(SyncPreKeys(user, clients))

  def postSessionReset(conv: ConvId, user: UserId, client: ClientId) = addRequest(PostSessionReset(conv, user, client))

  override def postValidateHandles(handles: Seq[Handle]): Future[SyncId] = addRequest(ValidateHandles(handles))
}

trait SyncHandler {
  def apply(req: SyncRequest): Future[SyncResult]
  def apply(req: SerialExecutionWithinConversation, lock: ConvLock): Future[SyncResult]
}

class AccountSyncHandler(zms: Signal[ZMessaging], otrClients: OtrClientsSyncHandler) extends SyncHandler {
  import Threading.Implicits.Background

  import com.waz.model.sync.SyncRequest._

  private def withZms(body: ZMessaging => Future[SyncResult]): Future[SyncResult] = zms.head flatMap body

  val accountHandler: PartialFunction[SyncRequest, Future[SyncResult]] = {
    case SyncSelfClients            => otrClients.syncSelfClients()
    case SyncClients(user)          => otrClients.syncClients(user)
    case SyncClientsLocation        => otrClients.syncClientsLocation()
    case SyncPreKeys(user, clients) => otrClients.syncPreKeys(Map(user -> clients.toSeq))
    case PostClientLabel(id, label) => otrClients.postLabel(id, label)

    case Unknown => Future successful SyncResult.Success
    case req: SerialExecutionWithinConversation => throw new IllegalArgumentException(s"trying to run $req without conv lock")
  }

  def zmsHandler(zms: ZMessaging): PartialFunction[SyncRequest, Future[SyncResult]] = {
    case SyncConversation(convs)               => zms.conversationSync.syncConversations(convs.toSeq)
    case SyncUser(u)                           => zms.usersSync.syncUsers(u.toSeq: _*)
    case SyncSearchQuery(query)                => zms.usersearchSync.syncSearchQuery(query)
    case ExactMatchHandle(query)               => zms.usersearchSync.exactMatchHandle(query)
    case SyncRichMedia(messageId)              => zms.richmediaSync.syncRichMedia(messageId)
    case DeletePushToken(token)                => zms.gcmSync.deleteGcmToken(token)
    case PostConnection(userId, name, message) => zms.connectionsSync.postConnection(userId, name, message)
    case PostConnectionStatus(userId, status)  => zms.connectionsSync.postConnectionStatus(userId, status)
    case SyncConversations                     => zms.conversationSync.syncConversations()
    case SyncTeam                              => zms.teamsSync.syncTeam()
    case SyncTeamMember(userId)                => zms.teamsSync.syncMember(userId)
    case SyncConnectedUsers                    => zms.usersSync.syncConnectedUsers()
    case SyncConnections                       => zms.connectionsSync.syncConnections()
    case SyncSelf                              => zms.usersSync.syncSelfUser()
    case DeleteAccount                         => zms.usersSync.deleteAccount()
    case PostSelf(info)                        => zms.usersSync.postSelfUser(info)
    case PostSelfPicture(_)                    => zms.usersSync.postSelfPicture()
    case PostAddressBook(ab)                   => zms.addressbookSync.postAddressBook(ab)
    case PostInvitation(i)                     => zms.invitationSync.postInvitation(i)
    case RegisterPushToken(token)              => zms.gcmSync.registerPushToken(token)
    case PostLiking(convId, liking)            => zms.reactionsSync.postReaction(convId, liking)
    case PostDeleted(convId, msgId)            => zms.messagesSync.postDeleted(convId, msgId)
    case PostLastRead(convId, time)            => zms.lastReadSync.postLastRead(convId, time)
    case PostOpenGraphMeta(conv, msg, time)    => zms.openGraphSync.postMessageMeta(conv, msg, time)
    case PostRecalled(convId, msg, recall)     => zms.messagesSync.postRecalled(convId, msg, recall)
    case PostSessionReset(conv, user, client)  => zms.otrSync.postSessionReset(conv, user, client)
    case PostReceipt(conv, msg, user, tpe)     => zms.messagesSync.postReceipt(conv, msg, user, tpe)
    case ValidateHandles(handles)              => zms.handlesSync.validateHandles(handles)
  }

  override def apply(req: SyncRequest): Future[SyncResult] =
    accountHandler.applyOrElse(req, { _: SyncRequest => withZms(zmsHandler(_).apply(req)) })

  override def apply(req: SerialExecutionWithinConversation, lock: ConvLock): Future[SyncResult] = withZms { zms =>
    implicit val convLock = lock

    req match {
      case PostMessage(convId, messageId, time)   => zms.messagesSync.postMessage(convId, messageId, time)
      case PostAssetStatus(cid, mid, exp, status) => zms.messagesSync.postAssetStatus(cid, mid, exp, status)
      case PostConvJoin(convId, u)                => zms.conversationSync.postConversationMemberJoin(convId, u.toSeq)
      case PostConvLeave(convId, u)               => zms.conversationSync.postConversationMemberLeave(convId, u)
      case PostConv(convId, u, name, team)        => zms.conversationSync.postConversation(convId, u, name, team)
      case PostConvName(convId, name)             => zms.conversationSync.postConversationName(convId, name)
      case PostConvState(convId, state)           => zms.conversationSync.postConversationState(convId, state)
      case PostTypingState(convId, ts)            => zms.typingSync.postTypingState(convId, ts)
      case PostCleared(convId, time)              => zms.clearedSync.postCleared(convId, time)
    }
  }
}
