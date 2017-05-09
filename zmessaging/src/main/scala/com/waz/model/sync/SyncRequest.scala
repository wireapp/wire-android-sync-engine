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
package com.waz.model.sync

import com.waz.api.EphemeralExpiration
import com.waz.model.AddressBook.AddressBookDecoder
import com.waz.model.UserData.ConnectionStatus
import com.waz.model.otr.ClientId
import com.waz.model.{SearchQuery, _}
import com.waz.sync.client.{ConversationsClient, UsersClient}
import com.waz.sync.queue.SyncJobMerger._
import com.waz.utils._
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.reflect.ClassTag

sealed abstract class SyncRequest {
  val cmd: SyncCommand

  def mergeKey: Any = cmd

  /**
   * Try merging, assuming that merge key is the same.
   */
  def merge(req: SyncRequest): MergeResult[SyncRequest] = if (this == req) Merged(this) else Unchanged

  /**
   * Checks if this requests is same or a part of given req.
   */
  def isDuplicateOf(req: SyncRequest): Boolean = this == req
}

sealed trait ConversationReference extends SyncRequest {
  val convId: ConvId
  override val mergeKey: Any = (cmd, convId)
}

// sync requests which should be serialized (executed one by one) in a conversation context
// if we executed them in parallel then we would end up with unexpected messages ordering (or other errors)
sealed trait SerialExecutionWithinConversation extends ConversationReference

object SyncRequest {
  import sync.{SyncCommand => Cmd}

  sealed abstract class BaseRequest(val cmd: SyncCommand) extends SyncRequest

  case object Unknown extends BaseRequest(Cmd.Unknown)
  case object SyncSelf extends BaseRequest(Cmd.SyncSelf)
  case object DeleteAccount extends BaseRequest(Cmd.DeleteAccount)
  case object SyncConversations extends BaseRequest(Cmd.SyncConversations)
  case object SyncConnections extends BaseRequest(Cmd.SyncConnections)
  case object SyncConnectedUsers extends BaseRequest(Cmd.SyncConnectedUsers)
  case object ResetGcmToken extends BaseRequest(Cmd.RegisterGcmToken)
  case object RegisterPushToken extends BaseRequest(Cmd.RegisterGcmToken)
  case object SyncSelfClients extends BaseRequest(Cmd.SyncSelfClients)
  case object SyncClientsLocation extends BaseRequest(Cmd.ValidateHandles)

  case class PostAddressBook(addressBook: AddressBook) extends BaseRequest(Cmd.PostAddressBook) {
    override def merge(req: SyncRequest) = mergeHelper[PostAddressBook](req)(Merged(_))
    override def isDuplicateOf(req: SyncRequest): Boolean = req.cmd == Cmd.PostAddressBook
  }

  case class PostInvitation(invitation: Invitation) extends BaseRequest(Cmd.PostInvitation) {
    override val mergeKey: Any = (cmd, invitation.id, invitation.method)
    override def merge(req: SyncRequest) = mergeHelper[PostInvitation](req)(Merged(_))
    override def isDuplicateOf(req: SyncRequest) = req.mergeKey == mergeKey
  }

  case class PostSelf(data: UserInfo) extends BaseRequest(Cmd.PostSelf) {
    override def merge(req: SyncRequest) = mergeHelper[PostSelf](req)(Merged(_))
  }

  case class DeleteGcmToken(token: GcmId) extends BaseRequest(Cmd.DeleteGcmToken) {
    override val mergeKey: Any = (cmd, token)
  }

  case class SyncSearchQuery(query: SearchQuery) extends BaseRequest(Cmd.SyncSearchQuery) {
    override val mergeKey: Any = (cmd, query)
  }

  case class SyncRichMedia(messageId: MessageId) extends BaseRequest(Cmd.SyncRichMedia) {
    override val mergeKey: Any = (cmd, messageId)
  }

  case class PostSelfPicture(assetId: Option[AssetId]) extends BaseRequest(Cmd.PostSelfPicture) {
    override def merge(req: SyncRequest) = mergeHelper[PostSelfPicture](req)(Merged(_))
  }

  sealed abstract class RequestForConversation(cmd: SyncCommand) extends BaseRequest(cmd) with ConversationReference

  case class SyncCallState(convId: ConvId, fromFreshNotification: Boolean) extends RequestForConversation(Cmd.SyncCallState) {
    override def merge(req: SyncRequest) = mergeHelper[SyncCallState](req)(other => Merged(other.copy(fromFreshNotification = fromFreshNotification || other.fromFreshNotification)))
  }

  case class PostConv(convId: ConvId, users: Seq[UserId], name: Option[String]) extends RequestForConversation(Cmd.PostConv) with SerialExecutionWithinConversation {
    override def merge(req: SyncRequest) = mergeHelper[PostConv](req)(Merged(_))
  }

  case class PostConvName(convId: ConvId, name: String) extends RequestForConversation(Cmd.PostConvName) with SerialExecutionWithinConversation {
    override def merge(req: SyncRequest) = mergeHelper[PostConvName](req)(Merged(_))
  }

  case class PostConvState(convId: ConvId, state: ConversationState) extends RequestForConversation(Cmd.PostConvState) with SerialExecutionWithinConversation {
    override def merge(req: SyncRequest) = mergeHelper[PostConvState](req)(other => Merged(copy(state = mergeConvState(state, other.state))))
  }

  case class PostLastRead(convId: ConvId, time: Instant) extends RequestForConversation(Cmd.PostLastRead) {
    override val mergeKey: Any = (cmd, convId)
    override def merge(req: SyncRequest) = mergeHelper[PostLastRead](req)(Merged(_))
  }

  case class PostCleared(convId: ConvId, time: Instant) extends RequestForConversation(Cmd.PostCleared) with SerialExecutionWithinConversation {
    override val mergeKey: Any = (cmd, convId)
    override def merge(req: SyncRequest) = mergeHelper[PostCleared](req) { other =>
      Merged(PostCleared(convId, time max other.time))
    }
  }

  case class PostTypingState(convId: ConvId, isTyping: Boolean) extends RequestForConversation(Cmd.PostTypingState) with SerialExecutionWithinConversation {
    override def merge(req: SyncRequest) = mergeHelper[PostTypingState](req)(Merged(_))
  }

  case class PostMessage(convId: ConvId, messageId: MessageId, editTime: Instant) extends RequestForConversation(Cmd.PostMessage) with SerialExecutionWithinConversation {
    override val mergeKey = (cmd, convId, messageId)
    override def merge(req: SyncRequest) = mergeHelper[PostMessage](req) { r =>
      // those requests are merged if message was edited multiple times (or unsent message was edited before sync is finished)
      // editTime == Instant.EPOCH is a special value, it marks initial message sync, we need to preserve that info
      // sync handler will check editTime and will just upload regular message (with current content) instead of an edit if it's EPOCH
      val time = if (editTime == Instant.EPOCH) Instant.EPOCH else editTime max r.editTime
      Merged(PostMessage(convId, messageId, time))
    }
  }

  case class PostOpenGraphMeta(convId: ConvId, messageId: MessageId, editTime: Instant) extends RequestForConversation(Cmd.PostOpenGraphMeta) {
    override val mergeKey = (cmd, convId, messageId)
    override def merge(req: SyncRequest) = mergeHelper[PostOpenGraphMeta](req)(r => Merged(PostOpenGraphMeta(convId, messageId, editTime max r.editTime)))
  }

  case class PostReceipt(convId: ConvId, messageId: MessageId, userId: UserId, tpe: ReceiptType) extends RequestForConversation(Cmd.PostReceipt) {
    override val mergeKey = (cmd, messageId, userId, tpe)
  }

  case class PostDeleted(convId: ConvId, messageId: MessageId) extends RequestForConversation(Cmd.PostDeleted) {
    override val mergeKey = (cmd, convId, messageId)
  }

  case class PostRecalled(convId: ConvId, msg: MessageId, recalledId: MessageId) extends RequestForConversation(Cmd.PostRecalled) {
    override val mergeKey = (cmd, convId, msg, recalledId)
  }

  case class PostAssetStatus(convId: ConvId, messageId: MessageId, exp: EphemeralExpiration, status: AssetStatus.Syncable) extends RequestForConversation(Cmd.PostAssetStatus) with SerialExecutionWithinConversation {
    override val mergeKey = (cmd, convId, messageId)
    override def merge(req: SyncRequest) = mergeHelper[PostAssetStatus](req)(Merged(_))
  }

  case class PostClientLabel(id: ClientId, label: String) extends BaseRequest(Cmd.PostClientLabel) {
    override val mergeKey = (cmd, id)
  }

  case class SyncClients(user: UserId) extends BaseRequest(Cmd.SyncClients) {
    override val mergeKey = (cmd, user)
  }

  case class SyncPreKeys(user: UserId, clients: Set[ClientId]) extends BaseRequest(Cmd.SyncPreKeys) {
    override val mergeKey = (cmd, user)

    override def merge(req: SyncRequest) = mergeHelper[SyncPreKeys](req) { other =>
      if (other.clients.forall(clients)) Merged(this)
      else Merged(SyncPreKeys(user, clients ++ other.clients))
    }

    override def isDuplicateOf(req: SyncRequest): Boolean = req match {
      case SyncPreKeys(u, cs) if u == user && clients.forall(cs) => true
      case _ => false
    }
  }

  case class PostLiking(convId: ConvId, liking: Liking) extends RequestForConversation(Cmd.PostLiking) {
    override val mergeKey = (cmd, convId, liking.id)
  }

  case class PostSessionReset(convId: ConvId, userId: UserId, client: ClientId) extends RequestForConversation(Cmd.PostSessionReset) {
    override val mergeKey: Any = (cmd, convId, userId, client)
  }

  sealed abstract class RequestForUser(cmd: SyncCommand) extends BaseRequest(cmd) {
    val userId: UserId
    override val mergeKey = (cmd, userId)
  }

  case class PostConnection(userId: UserId, name: String, message: String) extends RequestForUser(Cmd.PostConnection)

  case class PostConnectionStatus(userId: UserId, status: Option[ConnectionStatus]) extends RequestForUser(Cmd.PostConnectionStatus) {
    override def merge(req: SyncRequest) = mergeHelper[PostConnectionStatus](req)(Merged(_)) // always use incoming request value
  }

  case class SyncUser(users: Set[UserId]) extends BaseRequest(Cmd.SyncUser) {

    override def merge(req: SyncRequest) = mergeHelper[SyncUser](req) { other =>
      if (other.users.forall(users)) Merged(this)
      else {
        val union = users ++ other.users
        if (union.size <= UsersClient.IdsCountThreshold) Merged(SyncUser(union))
        else if (union.size == users.size + other.users.size) Unchanged
        else Updated(other.copy(other.users -- users))
      }
    }

    override def isDuplicateOf(req: SyncRequest): Boolean = req match {
      case SyncUser(us) if users.forall(us) => true
      case _ => false
    }
  }

  case class SyncConversation(convs: Set[ConvId]) extends BaseRequest(Cmd.SyncConversation) {

    override def merge(req: SyncRequest) = mergeHelper[SyncConversation](req) { other =>
      if (other.convs.forall(convs)) Merged(this)
      else {
        val union = convs ++ other.convs
        if (union.size <= ConversationsClient.IdsCountThreshold) Merged(SyncConversation(union))
        else if (union.size == convs.size + other.convs.size) Unchanged
        else Updated(other.copy(other.convs -- convs))
      }
    }

    override def isDuplicateOf(req: SyncRequest): Boolean = req match {
      case SyncConversation(cs) if convs.forall(cs) => true
      case _ => false
    }
  }

  case class PostConvJoin(convId: ConvId, users: Set[UserId]) extends RequestForConversation(Cmd.PostConvJoin) with SerialExecutionWithinConversation {
    override def merge(req: SyncRequest) = mergeHelper[PostConvJoin](req) { other => Merged(PostConvJoin(convId, users ++ other.users)) }

    override def isDuplicateOf(req: SyncRequest): Boolean = req match {
      case PostConvJoin(`convId`, us) if users.forall(us) => true
      case _ => false
    }
  }

  // leave endpoint on backend accepts only one user as parameter (no way to remove multiple users at once)
  case class PostConvLeave(convId: ConvId, user: UserId) extends RequestForConversation(Cmd.PostConvLeave) with SerialExecutionWithinConversation {
    override val mergeKey = (cmd, convId, user)
  }

  case class ValidateHandles(handles: Seq[Handle]) extends BaseRequest(Cmd.ValidateHandles) {
    override def merge(req: SyncRequest) = mergeHelper[ValidateHandles](req) {
      other => Merged(ValidateHandles(other.handles ++ handles.filter(!other.handles.contains(_))))
    }
    override def isDuplicateOf(req: SyncRequest): Boolean = req match {
      case ValidateHandles(otherHandles) => handles.sameElements(otherHandles)
      case _ => false
    }
  }

  private def mergeHelper[A <: SyncRequest : ClassTag](other: SyncRequest)(f: A => MergeResult[A]): MergeResult[A] = other match {
    case req: A if req.mergeKey == other.mergeKey => f(req)
    case _ => Unchanged
  }

  def mergeConvState(o: ConversationState, n: ConversationState) = {
    val a = if (o.archiveTime.exists(t => n.archiveTime.forall(_.isBefore(t)))) o else n
    val m = if (o.muteTime.exists(t => n.muteTime.forall(_.isBefore(t)))) o else n

    ConversationState(a.archived, a.archiveTime, m.muted, m.muteTime)
  }

  implicit lazy val Decoder: JsonDecoder[SyncRequest] = new JsonDecoder[SyncRequest] {
    import JsonDecoder._

    override def apply(implicit js: JSONObject): SyncRequest = {
      def convId = decodeId[ConvId]('conv)
      def userId = decodeId[UserId]('user)
      def messageId = decodeId[MessageId]('message)
      def users = decodeUserIdSeq('users).toSet

      SyncCommand.fromName(js.getString("cmd")) match {
        case Cmd.SyncUser              => SyncUser(users)
        case Cmd.SyncConversation      => SyncConversation(decodeConvIdSeq('convs).toSet)
        case Cmd.SyncSearchQuery       => SyncSearchQuery(SearchQuery.fromCacheKey(decodeString('queryCacheKey)))
        case Cmd.SyncCallState         => SyncCallState(convId, fromFreshNotification = false)
        case Cmd.PostConv              => PostConv(convId, decodeStringSeq('users).map(UserId(_)), 'name)
        case Cmd.PostConvName          => PostConvName(convId, 'name)
        case Cmd.PostConvState         => PostConvState(convId, JsonDecoder[ConversationState]('state))
        case Cmd.PostLastRead          => PostLastRead(convId, 'time)
        case Cmd.PostCleared           => PostCleared(convId, 'time)
        case Cmd.PostTypingState       => PostTypingState(convId, 'typing)
        case Cmd.PostConnectionStatus  => PostConnectionStatus(userId, opt('status, js => ConnectionStatus(js.getString("status"))))
        case Cmd.PostSelfPicture       => PostSelfPicture(decodeOptAssetId('asset))
        case Cmd.PostMessage           => PostMessage(convId, messageId, 'time)
        case Cmd.PostDeleted           => PostDeleted(convId, messageId)
        case Cmd.PostRecalled          => PostRecalled(convId, messageId, decodeId[MessageId]('recalled))
        case Cmd.PostAssetStatus       => PostAssetStatus(convId, messageId, EphemeralExpiration.getForMillis('ephemeral), JsonDecoder[AssetStatus.Syncable]('status))
        case Cmd.PostConvJoin          => PostConvJoin(convId, users)
        case Cmd.PostConvLeave         => PostConvLeave(convId, userId)
        case Cmd.PostConnection        => PostConnection(userId, 'name, 'message)
        case Cmd.DeleteGcmToken        => DeleteGcmToken(decodeId[GcmId]('token))
        case Cmd.SyncRichMedia         => SyncRichMedia(messageId)
        case Cmd.SyncSelf              => SyncSelf
        case Cmd.DeleteAccount         => DeleteAccount
        case Cmd.SyncConversations     => SyncConversations
        case Cmd.SyncConnectedUsers    => SyncConnectedUsers
        case Cmd.SyncConnections       => SyncConnections
        case Cmd.RegisterGcmToken      => ResetGcmToken
        case Cmd.RegisterPushToken     => RegisterPushToken
        case Cmd.PostSelf              => PostSelf(JsonDecoder[UserInfo]('user))
        case Cmd.PostAddressBook       => PostAddressBook(JsonDecoder.opt[AddressBook]('addressBook).getOrElse(AddressBook.Empty))
        case Cmd.PostInvitation        => PostInvitation(JsonDecoder[Invitation]('invitation))
        case Cmd.SyncSelfClients       => SyncSelfClients
        case Cmd.SyncClients           => SyncClients(userId)
        case Cmd.SyncClientLocation    => SyncClientsLocation
        case Cmd.SyncPreKeys           => SyncPreKeys(userId, decodeClientIdSeq('clients).toSet)
        case Cmd.PostClientLabel       => PostClientLabel(decodeId[ClientId]('client), 'label)
        case Cmd.PostLiking            => PostLiking(convId, JsonDecoder[Liking]('liking))
        case Cmd.PostSessionReset      => PostSessionReset(convId, userId, decodeId[ClientId]('client))
        case Cmd.PostOpenGraphMeta     => PostOpenGraphMeta(convId, messageId, 'time)
        case Cmd.PostReceipt           => PostReceipt(convId, messageId, userId, ReceiptType.fromName('type))
        case Cmd.ValidateHandles       => ValidateHandles('handles)
        case Cmd.Unknown               => Unknown
      }
    }
  }

  implicit lazy val Encoder: JsonEncoder[SyncRequest] = new JsonEncoder[SyncRequest] {
    import JsonEncoder._

    override def apply(req: SyncRequest): JSONObject = JsonEncoder { o =>
      def putId[A: Id](name: String, id: A) = o.put(name, implicitly[Id[A]].encode(id))

      o.put("cmd", req.cmd.name)

      req match {
        case user: RequestForUser => o.put("user", user.userId)
        case conv: RequestForConversation => o.put("conv", conv.convId)
        case _ => ()
      }

      req match {
        case SyncUser(users)                  => o.put("users", arrString(users.toSeq map ( _.str)))
        case SyncConversation(convs)          => o.put("convs", arrString(convs.toSeq map (_.str)))
        case SyncSearchQuery(queryCacheKey)   => o.put("queryCacheKey", queryCacheKey.cacheKey)
        case DeleteGcmToken(token)            => putId("token", token)
        case SyncRichMedia(messageId)         => putId("message", messageId)
        case PostSelfPicture(assetId)         => assetId.foreach(putId("asset", _))
        case PostMessage(_, messageId, time)  =>
          putId("message", messageId)
          o.put("time", time.toEpochMilli)

        case PostDeleted(_, messageId)        => putId("message", messageId)
        case PostRecalled(_, msg, recalled)   =>
          putId("message", msg)
          putId("recalled", recalled)

        case PostConnectionStatus(_, status)  => status foreach { status => o.put("status", status.code) }
        case PostConvJoin(_, users)           => o.put("users", arrString(users.toSeq map (_.str)))
        case PostConvLeave(_, user)           => putId("user", user)
        case PostOpenGraphMeta(_, messageId, time) =>
          putId("message", messageId)
          o.put("time", time.toEpochMilli)

        case PostReceipt(_, messageId, userId, tpe) =>
          putId("message", messageId)
          putId("user", userId)
          o.put("type", tpe)

        case PostConnection(_, name, message) =>
          o.put("name", name)
          o.put("message", message)

        case PostLastRead(_, time) =>
          o.put("time", time.toEpochMilli)

        case PostCleared(_, time) =>
          o.put("time", time.toEpochMilli)

        case PostAssetStatus(_, mid, exp, status) =>
          putId("message", mid)
          o.put("ephemeral", exp.milliseconds)
          o.put("status", JsonEncoder.encode(status))

        case PostSelf(info) => o.put("user", JsonEncoder.encode(info))
        case PostTypingState(_, typing) => o.put("typing", typing)
        case PostConvState(_, state) => o.put("state", JsonEncoder.encode(state))
        case PostConvName(_, name) => o.put("name", name)
        case PostConv(_, users, name) =>
          o.put("users", arrString(users.map(_.str)))
          name foreach { o.put("name", _) }
        case PostAddressBook(ab) => o.put("addressBook", JsonEncoder.encode(ab))
        case PostInvitation(i) => o.put("invitation", JsonEncoder.encode(i))
        case PostLiking(_, liking) =>
          o.put("liking", JsonEncoder.encode(liking))
        case PostClientLabel(id, label) =>
          o.put("client", id.str)
          o.put("label", label)
        case PostSessionReset(_, user, client) =>
          o.put("client", client.str)
          o.put("user", user)
        case SyncClients(user) => o.put("user", user.str)
        case SyncPreKeys(user, clients) =>
          o.put("user", user.str)
          o.put("clients", arrString(clients.toSeq map (_.str)))
        case SyncCallState(_, _) => () // nothing to do
        case SyncSelf | DeleteAccount | SyncConversations | SyncConnections | SyncConnectedUsers | ResetGcmToken | RegisterPushToken | SyncSelfClients | SyncClientsLocation | Unknown => () // nothing to do
        case ValidateHandles(handles) => o.put("handles", arrString(handles.map(_.toString)))
      }
    }
  }
}

object SerialConvRequest {
  def unapply(req: SyncRequest): Option[ConvId] = req match {
    case p: ConversationReference with SerialExecutionWithinConversation => Some(p.convId)
    case _ => None
  }
}
