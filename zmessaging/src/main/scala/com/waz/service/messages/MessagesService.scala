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
package com.waz.service.messages

import java.util.Date

import android.util.Base64
import com.waz.ZLog._
import com.waz.api.Message.{Status, Type}
import com.waz.api.{ErrorResponse, Message, Verification}
import com.waz.content.{EditHistoryStorage, ReactionsStorage}
import com.waz.model.AssetStatus.UploadCancelled
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.Asset.Original
import com.waz.model.GenericContent._
import com.waz.model.{IdentityChangedError, MessageId, _}
import com.waz.service._
import com.waz.service.assets.AssetService
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.otr.VerificationStateUpdater
import com.waz.service.otr.VerificationStateUpdater.{ClientAdded, ClientUnverified, MemberAdded, VerificationChange}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.RichFuture.traverseSequential
import com.waz.utils._
import com.waz.utils.crypto.AESUtils
import com.waz.utils.events.{EventContext, Signal}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.Future.{successful, traverse}
import scala.util.Success

class MessagesService(selfUserId: UserId, val content: MessagesContentUpdater, edits: EditHistoryStorage, assets: AssetService, users: UserService, convs: ConversationsContentUpdater,
    reactions: ReactionsStorage, network: NetworkModeService, sync: SyncServiceHandle, verificationUpdater: VerificationStateUpdater, timeouts: Timeouts) {
  import Threading.Implicits.Background
  private implicit val logTag: LogTag = logTagFor[MessagesService]
  private implicit val ec = EventContext.Global

  import assets._
  import content._
  import convs._
  import users._

  val messageEventProcessingStage = EventScheduler.Stage[MessageEvent] { (convId, events) =>
    verbose(s"got events to process: $events")
    processConvWithRemoteId(convId, retryAsync = true) { conv =>
      verbose(s"processing events for conv: $conv, events: $events")
      processEvents(conv, events)
    }
  }

  verificationUpdater.updateProcessor = { update =>
    addMessagesAfterVerificationUpdate(update.convUpdates, update.convUsers, update.changes) map { _ => Unit }
  }

  private[service] def processEvents(conv: ConversationData, events: Seq[MessageEvent]): Future[Set[MessageData]] = {
    val afterCleared = events.filter(e => conv.cleared.isBefore(e.time.instant))

    val recalls = afterCleared collect { case GenericMessageEvent(_, _, time, from, msg @ GenericMessage(_, MsgRecall(_))) => (msg, from, time.instant) }

    val edits = afterCleared collect { case GenericMessageEvent(_, _, time, from, msg @ GenericMessage(_, MsgEdit(_, _))) => (msg, from, time.instant) }

    for {
      as    <- updateAssets(afterCleared)
      msgs  = afterCleared map { createMessage(conv, _) } filter (_ != MessageData.Empty)
      _     = verbose(s"messages from events: $msgs")
      res   <- content.addMessages(conv.id, msgs)
      _     <- updateLastReadFromOwnMessages(conv.id, msgs)
      _     <- deleteCancelled(as)
      _     <- Future.traverse(recalls) { case (GenericMessage(id, MsgRecall(ref)), user, time) => recallMessage(conv.id, ref, user, MessageId(id.str), time, Message.Status.SENT) }
      _     <- RichFuture.traverseSequential(edits) { case (gm @ GenericMessage(id, MsgEdit(ref, Text(text, mentions, links))), user, time) => applyMessageEdit(conv.id, user, time, gm) }
    } yield res
  }

  private def updateAssets(events: Seq[MessageEvent]) = {

    def update(id: Uid, convId: RConvId, time: Date, ct: Any, msg: GenericMessage, dataId: Option[RAssetDataId], data: Option[Array[Byte]]): Future[Option[AssetData]] =
      ct match {
        case asset: Asset =>
          updateAsset(AssetId(id.str), convId, asset, dataId, time) map { Some(_) }
        case ImageAsset(tag, width, height, origWidth, origHeight, mime, size, None, _) =>
          warn(s"got otr asset event without otr key: $msg")
          val img = ImageData(tag, mime.str, width, height, origWidth, origHeight, size, dataId, sent = true, data64 = data flatMap { arr => LoggedTry(Base64.encodeToString(arr, Base64.DEFAULT)).toOption })
          updateImageAsset(AssetId(id.str), convId, img) map { Some(_) }
        case ImageAsset(tag, width, height, origWidth, origHeight, mime, size, Some(key), sha) =>
          val img = data.fold {
            ImageData(tag, mime.str, width, height, origWidth, origHeight, size, dataId, sent = true, otrKey = Some(key), sha256 = sha)
          } { img =>
            val data64 = if (sha.forall(_.str == sha2(img))) LoggedTry(Base64.encodeToString(AESUtils.decrypt(key, img), Base64.DEFAULT)).toOption else None
            ImageData(tag, mime.str, width, height, origWidth, origHeight, size, dataId, sent = true, otrKey = Some(key), sha256 = sha, data64 = data64)
          }
          updateImageAsset(AssetId(id.str), convId, img) map { Some(_) }
        case Ephemeral(_, c) =>
          update(id, convId, time, c, msg, dataId, data)
        case _ =>
          Future successful None
      }

    Future.sequence(events.collect {
      case GenericMessageEvent(_, convId, time, from, msg @ GenericMessage(id, ct)) =>
        update(id, convId, time, ct, msg, None, None)

      case GenericAssetEvent(_, convId, time, from, msg @ GenericMessage(id, ct), dataId, data) =>
        update(id, convId, time, ct, msg, Some(dataId), data)
    }) map { _.flatten }
  }

  private def updateLastReadFromOwnMessages(convId: ConvId, msgs: Seq[MessageData]) =
    msgs.reverseIterator.find(_.userId == selfUserId).fold2(Future.successful(None), msg => updateConversationLastRead(convId, msg.time))

  private def deleteCancelled(as: Seq[AssetData]) = {
    val toRemove = as collect {
      case AnyAssetData(id, _, _, _, _, _, _, _, _, UploadCancelled, _) => id
    }
    if (toRemove.isEmpty) Future.successful(())
    else for {
      _ <- Future.traverse(toRemove)(id => content.messagesStorage.remove(MessageId(id.str)))
      _ <- assets.storage.remove(toRemove)
    } yield ()
  }

  private def createMessage(conv: ConversationData, event: MessageEvent) = {
    val id = MessageId.fromUid(event.id)
    val convId = conv.id

    def content(id: MessageId, msgContent: Any, from: UserId, time: Instant, proto: GenericMessage): MessageData = msgContent match {
      case Text(text, mentions, links) =>
        val (tpe, content) = MessageData.messageContent(text, mentions, links)
        MessageData(id, conv.id, tpe, from, content, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case Knock() =>
        MessageData(id, conv.id, Message.Type.KNOCK, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case Reaction(_, _) => MessageData.Empty
      case Asset(_, _, UploadCancelled) => MessageData.Empty
      case Asset(Some(Original(Mime.Video(), _, _, _, _)), _, _) =>
        MessageData(id, convId, Message.Type.VIDEO_ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case Asset(Some(Original(Mime.Audio(), _, _, _, _)), _, _) =>
        MessageData(id, convId, Message.Type.AUDIO_ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case Asset(Some(Original(Mime.Image(), _, _, _, _)), _, _) =>
        MessageData(id, convId, Message.Type.ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case Asset(_, _, _) =>
        MessageData(id, convId, Message.Type.ANY_ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case Location(_, _, _, _) =>
        MessageData(id, convId, Message.Type.LOCATION, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
      case LastRead(remoteId, timestamp) => MessageData.Empty
      case Cleared(remoteId, timestamp) => MessageData.Empty
      case MsgDeleted(_, _) => MessageData.Empty
      case MsgRecall(_) => MessageData.Empty
      case MsgEdit(_, _) => MessageData.Empty
      case Receipt(_) => MessageData.Empty
      case Ephemeral(expiry, ct) =>
        content(id, ct, from, time, proto).copy(ephemeral = expiry)
      case _ =>
        error(s"unexpected generic message content: $msgContent")
        // TODO: this message should be processed again after app update, maybe future app version will understand it
        MessageData(id, conv.id, Message.Type.UNKNOWN, from, time = time, localTime = event.localTime.instant, protos = Seq(proto))
    }

    def assetContent(id: MessageId, ct: Any, from: UserId, time: Instant, msg: GenericMessage): MessageData = ct match {
      case asset @ Asset(Some(Original(Mime.Video(), _, _, _, _)), _, _) =>
        MessageData(id, convId, Message.Type.VIDEO_ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(msg))
      case asset @ Asset(Some(Original(Mime.Audio(), _, _, _, _)), _, _) =>
        MessageData(id, convId, Message.Type.AUDIO_ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(msg))
      case asset @ Asset(Some(Original(Mime.Image(), _, _, _, _)), _, _) =>
        MessageData(id, convId, Message.Type.ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(msg))
      case asset: Asset =>
        MessageData(id, convId, Message.Type.ANY_ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(msg))
      case im: ImageAsset => //deprecated - only used by clients sending assetsV2
        MessageData(id, convId, Message.Type.ASSET, from, time = time, localTime = event.localTime.instant, protos = Seq(msg))
      case Ephemeral(expiry, ect) =>
        assetContent(id, ect, from, time, msg).copy(ephemeral = expiry)
      case _ =>
        error(s"unexpected generic asset content: $msg")
        // TODO: this message should be processed again after app update, maybe future app version will understand it
        MessageData(id, conv.id, Message.Type.UNKNOWN, from, time = time, localTime = event.localTime.instant, protos = Seq(msg))
    }

    event match {
      case ConnectRequestEvent(_, _, time, from, text, recipient, name, email) =>
        MessageData(id, convId, Message.Type.CONNECT_REQUEST, from, MessageData.textContent(text), recipient = Some(recipient), email = email, name = Some(name), time = time.instant, localTime = event.localTime.instant)
      case RenameConversationEvent(_, _, time, from, name) =>
        MessageData(id, convId, Message.Type.RENAME, from, name = Some(name), time = time.instant, localTime = event.localTime.instant)
      case MemberJoinEvent(_, _, time, from, userIds, firstEvent) =>
        MessageData(id, convId, Message.Type.MEMBER_JOIN, from, members = userIds.toSet, time = time.instant, localTime = event.localTime.instant, firstMessage = firstEvent)
      case MemberLeaveEvent(_, _, time, from, userIds) =>
        MessageData(id, convId, Message.Type.MEMBER_LEAVE, from, members = userIds.toSet, time = time.instant, localTime = event.localTime.instant)
      case MissedCallEvent(_, _, time, from) =>
        MessageData(id, convId, Message.Type.MISSED_CALL, from, time = time.instant, localTime = event.localTime.instant)
      case _: VoiceChannelDeactivateEvent =>
        MessageData.Empty // don't add any message, interesting case is handled with MissedCallEvent extractor
      case OtrErrorEvent(_, _, time, from, IdentityChangedError(_, _)) =>
        MessageData (id, conv.id, Message.Type.OTR_IDENTITY_CHANGED, from, time = time.instant, localTime = event.localTime.instant)
      case OtrErrorEvent(_, _, time, from, otrError) =>
        MessageData (id, conv.id, Message.Type.OTR_ERROR, from, time = time.instant, localTime = event.localTime.instant)
      case GenericMessageEvent(_, _, time, from, proto @ GenericMessage(uid, msgContent)) =>
        content(MessageId(uid.str), msgContent, from, time.instant, proto)
      case GenericAssetEvent(_, _, time, from, proto @ GenericMessage(uid, msgContent), dataId, data) =>
        assetContent(MessageId(uid.str), msgContent, from, time.instant, proto)
      case _ =>
        warn(s"Unexpected event for addMessage: $event")
        MessageData.Empty
    }
  }

  def getIncomingMessages = messagesStorage.getIncomingMessages flatMap { msgs =>
    Signal.future {
      convs.storage.getAll(msgs.map(_.convId).distinct) map { cs =>
        val muted = cs.flatten.filter(_.muted).map(_.id).toSet
        msgs filterNot { msg => muted(msg.convId) }
      }
    }
  }

  def recallMessage(convId: ConvId, msgId: MessageId, userId: UserId, systemMsgId: MessageId = MessageId(), time: Instant = Instant.now(), state: Message.Status = Message.Status.PENDING) =
    content.getMessage(msgId) flatMap {
      case Some(msg) if msg.convId != convId =>
        error(s"can not recall message belonging to other conversation: $msg, requested by $userId")
        Future successful None
      case Some(msg) if msg.canRecall(convId, userId) =>
        content.deleteOnUserRequest(Seq(msgId)) flatMap { _ =>
          val recall = MessageData(systemMsgId, convId, Message.Type.RECALLED, time = msg.time, editTime = time max msg.time, userId = userId, state = state, protos = Seq(GenericMessage(systemMsgId.uid, MsgRecall(msgId))))
          if (userId == selfUserId) Future successful Some(recall) // don't save system message for self user
          else content.addMessage(recall)
        }
      case Some(msg) if msg.isEphemeral =>
        // ephemeral message expired on other device, or on receiver side
        content.deleteOnUserRequest(Seq(msgId)) map { _ => None }
      case msg =>
        warn(s"can not recall $msg, requested by $userId")
        Future successful None
    }

  def applyMessageEdit(convId: ConvId, userId: UserId, time: Instant, gm: GenericMessage) = Serialized.future("applyMessageEdit", convId) {

    def findLatestUpdate(id: MessageId): Future[Option[MessageData]] =
      content.getMessage(id) flatMap {
        case Some(msg) => Future successful Some(msg)
        case None =>
          edits.get(id) flatMap {
            case Some(EditHistory(_, updated, _)) => findLatestUpdate(updated)
            case None => Future successful None
          }
      }

    gm match {
      case GenericMessage(id, MsgEdit(msgId, Text(text, mentions, links))) =>

        def applyEdit(msg: MessageData) = for {
            _ <- edits.insert(EditHistory(msg.id, MessageId(id.str), time))
            (tpe, ct) = MessageData.messageContent(text, mentions, links, weblinkEnabled = true)
            res <- content.addMessage(MessageData(MessageId(id.str), convId, tpe, userId, ct, Seq(gm), time = msg.time, localTime = msg.localTime, editTime = time))
            _ <- content.deleteOnUserRequest(Seq(msg.id))
        } yield res

        content.getMessage(msgId) flatMap {
          case Some(msg) if msg.userId == userId && msg.convId == convId =>
            verbose(s"got edit event for msg: $msg")
            applyEdit(msg)
          case _ =>
            // original message was already deleted, let's check if it was already updated
            edits.get(msgId) flatMap {
              case Some(EditHistory(_, updated, editTime)) if editTime <= time =>
                verbose(s"message $msgId has already been updated, discarding later update")
                Future successful None

              case Some(EditHistory(_, updated, editTime)) =>
                // this happens if message has already been edited locally,
                // but that edit is actually newer than currently received one, so we should revert it
                // we always use only the oldest edit for given message (as each update changes the message id)
                verbose(s"message $msgId has already been updated, will overwrite new message")
                findLatestUpdate(updated) flatMap {
                  case Some(msg) => applyEdit(msg)
                  case None =>
                    error(s"Previously updated message was not found for: $gm")
                    Future successful None
                }

              case None =>
                verbose(s"didn't find the original message for edit: $gm")
                Future successful None
            }
        }
      case _ =>
        error(s"invalid message for applyMessageEdit: $gm")
        Future successful None
    }
  }

  def addTextMessage(convId: ConvId, content: String, mentions: Map[UserId, String] = Map.empty): Future[MessageData] = {
    verbose(s"addTextMessage($convId, $content, $mentions)")
    val (tpe, ct) = MessageData.messageContent(content, mentions, weblinkEnabled = true)
    verbose(s"parsed content: $ct")
    val id = MessageId()
    addLocalMessage(MessageData(id, convId, tpe, selfUserId, ct, protos = Seq(GenericMessage(id.uid, Text(content, mentions, Nil))))) // FIXME: links
  }

  def addLocationMessage(convId: ConvId, content: Location): Future[MessageData] = {
    verbose(s"addLocationMessage($convId, $content)")
    val id = MessageId()
    addLocalMessage(MessageData(id, convId, Type.LOCATION, selfUserId, protos = Seq(GenericMessage(id.uid, content))))
  }

  //TODO why do we put a proto in here when we have sent any yet? - compare with addAssetMessage
  def addImageMessage(convId: ConvId, assetId: AssetId, width: Int, height: Int) = {
    val id = MessageId(assetId.str)
    addLocalMessage(MessageData(id, convId, Message.Type.ASSET, selfUserId, protos = Seq(GenericMessage(id.uid, Asset(Original(Mime("image/jpeg"), 0, None, Some(AssetMetaData.Image(Dim2(width, height), Some(ImageData.Tag.Medium)))))))))
  }

  //TODO why do we have the withSelfUserFuture here but not in other methods?
  def addAssetMessage(convId: ConvId, assetId: AssetId, mime: Mime): Future[MessageData] = withSelfUserFuture { selfUserId =>
    val tpe = mime match {
      case Mime.Video() => Message.Type.VIDEO_ASSET
      case Mime.Audio() => Message.Type.AUDIO_ASSET
      case _            => Message.Type.ANY_ASSET
    }
    addLocalMessage(MessageData(MessageId(assetId.str), convId, tpe, selfUserId))
  }

  def addRenameConversationMessage(convId: ConvId, selfUserId: UserId, name: String) =
    updateOrCreateLocalMessage(convId, Message.Type.RENAME, _.copy(name = Some(name)), MessageData(MessageId(), convId, Message.Type.RENAME, selfUserId, name = Some(name)))

  def addConnectRequestMessage(convId: ConvId, fromUser: UserId, toUser: UserId, message: String, name: String, fromSync: Boolean = false) = {
    val msg = MessageData(
      MessageId(), convId, Message.Type.CONNECT_REQUEST, fromUser, content = MessageData.textContent(message), name = Some(name), recipient = Some(toUser),
      time = if (fromSync) MessageData.UnknownInstant else Instant.now)

    if (fromSync) messagesStorage.insert(msg) else addLocalMessage(msg)
  }

  def addKnockMessage(convId: ConvId, selfUserId: UserId) = {
    debug(s"addKnockMessage($convId, $selfUserId)")
    addLocalMessage(MessageData(MessageId(), convId, Message.Type.KNOCK, selfUserId))
  }

  def addDeviceStartMessages(convs: Seq[ConversationData], selfUserId: UserId): Future[Set[MessageData]] =
    Serialized.future('addDeviceStartMessages)(traverse(convs filter isGroupOrOneToOne) { conv =>
      messagesStorage.getLastMessage(conv.id) map {
        case None =>    Some(MessageData(MessageId(), conv.id, Message.Type.STARTED_USING_DEVICE, selfUserId, time = Instant.EPOCH))
        case Some(_) => None
      }
    } flatMap { msgs =>
      messagesStorage.insert(msgs.flatten)
    })

  private def isGroupOrOneToOne(conv: ConversationData) = conv.convType == ConversationType.Group || conv.convType == ConversationType.OneToOne

  def addMessagesAfterVerificationUpdate(updates: Seq[(ConversationData, ConversationData)], convUsers: Map[ConvId, Seq[UserData]], changes: Map[UserId, VerificationChange]) =
    Future.traverse(updates) {
      case (prev, up) if up.verified == Verification.VERIFIED => addOtrVerifiedMessage(up.id)
      case (prev, up) if prev.verified == Verification.VERIFIED =>
        val convId = up.id
        val changedUsers = convUsers(convId).filter(!_.isVerified).flatMap { u => changes.get(u.id).map(u.id -> _) }
        val (users, change) =
          if (changedUsers.forall(c => c._2 == ClientAdded || c._2 == MemberAdded)) (changedUsers map (_._1), ClientAdded)
          else (changedUsers collect { case (user, ClientUnverified) => user }, ClientUnverified)

        val (self, other) = users.partition(_ == selfUserId)
        for {
          _ <- if (self.nonEmpty) addOtrUnverifiedMessage(convId, Seq(selfUserId), change) else Future.successful(())
          _ <- if (other.nonEmpty) addOtrUnverifiedMessage(convId, other, change) else Future.successful(())
        } yield ()
      case _ =>
        Future.successful(())
    }

  def addHistoryLostMessages(cs: Seq[ConversationData], selfUserId: UserId): Future[Set[MessageData]] = {
    // TODO: those messages should include information about what was actually changed
    traverseSequential(cs) { conv =>
      messagesStorage.getLastMessage(conv.id) map {
        case Some(msg) if msg.msgType != Message.Type.STARTED_USING_DEVICE =>
          Some(MessageData(MessageId(), conv.id, Message.Type.HISTORY_LOST, selfUserId, time = msg.time.plusMillis(1)))
        case _ =>
          // conversation has no messages or has STARTED_USING_DEVICE msg,
          // it means that conv was just created and we don't need to add history lost msg
          None
      }
    } flatMap { msgs =>
      messagesStorage.insert(msgs.flatten) flatMap { added =>
        // mark messages read if there is no other unread messages
        val times: Map[ConvId, Instant] = added.map(m => m.convId -> m.time) (breakOut)
        convs.storage.updateAll2(times.keys, { c =>
          val t = times(c.id)
          if (c.lastRead.toEpochMilli == t.toEpochMilli - 1) c.copy(lastRead = t) else c
        }) map { _ => added }
      }
    }
  }

  def addMemberJoinMessage(convId: ConvId, creator: UserId, users: Set[UserId]) = {
    verbose(s"addMemberJoinMessage($convId, $creator, $users)")

    def updateOrCreate(added: Set[UserId]) = {
      def update(msg: MessageData) = {
        msg.copy(members = msg.members ++ added)
      }
      def create = MessageData(MessageId(), convId, Message.Type.MEMBER_JOIN, creator, members = added)
      updateOrCreateLocalMessage(convId, Message.Type.MEMBER_JOIN, update, create)
    }

    // check if we have local leave message with same users
    messagesStorage.lastLocalMessage(convId, Message.Type.MEMBER_LEAVE) flatMap {
      case Some(msg) if users.exists(msg.members) =>
        val toRemove = msg.members -- users
        val toAdd = users -- msg.members
        if (toRemove.isEmpty) deleteMessage(msg) // FIXME: race condition
        else updateMessage(msg.id)(_.copy(members = toRemove)) // FIXME: race condition

        if (toAdd.isEmpty) successful(()) else updateOrCreate(toAdd)
      case _ =>
        updateOrCreate(users)
    }
  }

  def removeLocalMemberJoinMessage(convId: ConvId, users: Set[UserId]) = {
    messagesStorage.lastLocalMessage(convId, Message.Type.MEMBER_JOIN) flatMap {
      case Some(msg) =>
        val members = msg.members -- users
        if (members.isEmpty) {
          deleteMessage(msg)
        } else {
          updateMessage(msg.id) { _.copy(members = members) } // FIXME: possible race condition with addMemberJoinMessage or sync
        }
      case _ =>
        warn("removeLocalMemberJoinMessage: no local join message found")
        CancellableFuture.successful(())
    }
  }

  def addMemberLeaveMessage(convId: ConvId, selfUserId: UserId, user: UserId) = {
    // check if we have local join message with this user and just remove him from the list
    messagesStorage.lastLocalMessage(convId, Message.Type.MEMBER_JOIN) flatMap {
      case Some(msg) if msg.members == Set(user) => deleteMessage(msg) // FIXME: race condition
      case Some(msg) if msg.members(user) => updateMessage(msg.id)(_.copy(members = msg.members - user)) // FIXME: race condition
      case _ =>
        // check for local MemberLeave message before creating new one
        def newMessage = MessageData(MessageId(), convId, Message.Type.MEMBER_LEAVE, selfUserId, members = Set(user))
        def update(msg: MessageData) = msg.copy(members = msg.members + user)
        updateOrCreateLocalMessage(convId, Message.Type.MEMBER_LEAVE, update, newMessage)
    }
  }

  def addOtrVerifiedMessage(convId: ConvId): Future[Option[MessageData]] =
    messagesStorage.getLastMessage(convId) flatMap {
      case Some(msg) if msg.msgType == Message.Type.OTR_UNVERIFIED || msg.msgType == Message.Type.OTR_DEVICE_ADDED =>
        verbose(s"addOtrVerifiedMessage, removing previous message: $msg")
        messagesStorage.delete(msg.id) map { _ => None }
      case _ =>
        withSelfUserFuture { selfUser =>
          addLocalMessage(MessageData(MessageId(), convId, Message.Type.OTR_VERIFIED, selfUser), Status.SENT) map { Some(_) }
        }
    }

  def addOtrUnverifiedMessage(convId: ConvId, users: Seq[UserId], change: VerificationChange): Future[Option[MessageData]] = {
      val msgType = if (change == ClientUnverified) Message.Type.OTR_UNVERIFIED else Message.Type.OTR_DEVICE_ADDED
      withSelfUserFuture { selfUser =>
        addLocalMessage(MessageData(MessageId(), convId, msgType, selfUser, members = users.toSet), Status.SENT) map { Some(_) }
      }
    }

  def retryMessageSending(conv: ConvId, msgId: MessageId) =
    updateMessage(msgId) { msg =>
      if (msg.state == Status.SENT || msg.state == Status.PENDING) msg
      else msg.copy(state = Status.PENDING)
    } .flatMap {
      case Some(msg) => sync.postMessage(msg.id, conv, msg.editTime) map (Some(_))
      case _ => successful(None)
    }

  def messageSent(convId: ConvId, msg: MessageData) = {
    updateMessage(msg.id) { m => m.copy(state = Message.Status.SENT, expiryTime = m.ephemeral.expiryFromNow()) } andThen {
      case Success(Some(m)) => content.messagesStorage.onMessageSent ! m
    }
  }

  def messageDeliveryFailed(convId: ConvId, msg: MessageData, error: ErrorResponse) =
    updateMessageState(convId, msg.id, Message.Status.FAILED) andThen {
      case Success(Some(m)) => content.messagesStorage.onMessageFailed ! (m, error)
    }

  def updateMessageState(convId: ConvId, messageId: MessageId, state: Message.Status) =
    updateMessage(messageId) { _.copy(state = state) }

  def markMessageRead(convId: ConvId, id: MessageId) =
    if (network.isOfflineMode) CancellableFuture.successful(None)
    else
      updateMessage(id) { msg =>
        if (msg.state == Status.FAILED) msg.copy(state = Status.FAILED_READ)
        else msg
      }
}
