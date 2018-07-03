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

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api
import com.waz.api.IConversation.{Access, AccessRole}
import com.waz.api.MessageContent.Asset.ErrorHandler
import com.waz.api.NetworkMode.{OFFLINE, WIFI}
import com.waz.api.impl._
import com.waz.api.{ImageAssetFactory, Message, NetworkMode}
import com.waz.content._
import com.waz.model.ConversationData.{ConversationType, getAccessAndRoleForGroupConv}
import com.waz.model.GenericContent.{Location, MsgEdit}
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.AccountsService.InForeground
import com.waz.service._
import com.waz.service.assets.AssetService
import com.waz.service.conversation.ConversationsService.generateTempConversationId
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.service.tracking.TrackingService
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.{ConversationsClient, ErrorOr}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.Locales.currentLocaleOrdering
import com.waz.utils._
import com.waz.utils.events.EventStream
import com.waz.utils.wrappers.URI
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.control.NonFatal

trait ConversationsUiService {

  def sendMessage(convId: ConvId, uri: URI, errorHandler: ErrorHandler): Future[Option[MessageData]]
  def sendMessage(convId: ConvId, audioAsset: AssetForUpload, errorHandler: ErrorHandler): Future[Option[MessageData]]
  def sendMessage(convId: ConvId, m: SensitiveString, mentions: Map[UserId, Name] = Map.empty): Future[Option[MessageData]]
  def sendMessage(convId: ConvId, jpegData: Array[Byte]): Future[Option[MessageData]]
  def sendMessage(convId: ConvId, imageAsset: ImageAsset): Future[Option[MessageData]]
  def sendMessage(convId: ConvId, location: Location): Future[Option[MessageData]]

  def updateMessage(convId: ConvId, id: MessageId, text: SensitiveString): Future[Option[MessageData]]

  def deleteMessage(convId: ConvId, id: MessageId): Future[Unit]
  def recallMessage(convId: ConvId, id: MessageId): Future[Option[MessageData]]

  def setConversationArchived(id: ConvId, archived: Boolean): Future[Option[ConversationData]]
  def setConversationMuted(id: ConvId, muted: Boolean): Future[Option[ConversationData]]
  def setConversationName(id: ConvId, name: Name): Future[Option[ConversationData]]

  def addConversationMembers(conv: ConvId, users: Set[UserId]): Future[Option[SyncId]]
  def removeConversationMember(conv: ConvId, user: UserId): Future[Option[SyncId]]

  def leaveConversation(conv: ConvId): Future[Unit]
  def clearConversation(id: ConvId): Future[Option[ConversationData]]
  def findGroupConversations(prefix: SearchKey, limit: Int, handleOnly: Boolean): Future[Seq[ConversationData]]
  def knock(id: ConvId): Future[Option[MessageData]]
  def setLastRead(convId: ConvId, msg: MessageData): Future[Option[ConversationData]]

  def setEphemeral(id: ConvId, expiration: Option[FiniteDuration]): Future[Unit]
  def setEphemeralGlobal(id: ConvId, expiration: Option[FiniteDuration]): ErrorOr[Unit]

  //conversation creation methods
  def getOrCreateOneToOneConversation(toUser: UserId): Future[ConversationData]
  def createGroupConversation(name: Option[Name] = None, members: Set[UserId] = Set.empty, teamOnly: Boolean = false): Future[(ConversationData, SyncId)]

  def assetUploadCancelled : EventStream[Mime]
  def assetUploadFailed    : EventStream[ErrorResponse]
}

class ConversationsUiServiceImpl(selfUserId:      UserId,
                                 teamId:          Option[TeamId],
                                 assets:          AssetService,
                                 users:           UserService,
                                 usersStorage:    UsersStorage,
                                 messages:        MessagesService,
                                 messagesStorage: MessagesStorage,
                                 messagesContent: MessagesContentUpdater,
                                 members:         MembersStorage,
                                 assetStorage:    AssetsStorage,
                                 convsContent:    ConversationsContentUpdater,
                                 convStorage:     ConversationStorage,
                                 network:         NetworkModeService,
                                 convs:           ConversationsService,
                                 sync:            SyncServiceHandle,
                                 client:          ConversationsClient,
                                 accounts:        AccountsService,
                                 tracking:        TrackingService,
                                 errors:          ErrorsService) extends ConversationsUiService {
  import ConversationsUiService._
  import Threading.Implicits.Background

  override val assetUploadCancelled = EventStream[Mime]() //size, mime
  override val assetUploadFailed    = EventStream[ErrorResponse]()

  override def sendMessage(convId: ConvId, m: SensitiveString, mentions: Map[UserId, Name] = Map.empty) =
    for {
      msg <- messages.addTextMessage(convId, m, mentions)
      _   <- updateLastRead(msg)
      _   <- sync.postMessage(msg.id, convId, msg.editTime)
    } yield Some(msg)

  override def sendMessage(convId: ConvId, location: Location) =
    for {
      msg <- messages.addLocationMessage(convId, location)
      _   <- updateLastRead(msg)
      _   <- sync.postMessage(msg.id, convId, msg.editTime)
    } yield Some(msg)

  override def sendMessage(convId: ConvId, uri: URI, errorHandler: ErrorHandler) = {
    val asset = ContentUriAssetForUpload(AssetId(), uri)
    asset.mimeType.flatMap {
      case Mime.Image() => sendImageMessage(convId, ImageAssetFactory.getImageAsset(uri))
      case _            => sendMessage(convId, asset, errorHandler)
    }
  }

  override def sendMessage(convId: ConvId, in: AssetForUpload, handler: ErrorHandler) = {
    for {
      mime           <- in.mimeType
      Some(remoteId) <- convsContent.convById(convId).map(_.map(_.remoteId))
      asset          <- assets.addAsset(in, remoteId)
      message        <- messages.addAssetMessage(convId, asset)
      _              <- updateLastRead(message)
      size           <- in.sizeInBytes
      _              <- Future.successful(tracking.assetContribution(asset.id, selfUserId))
      shouldSend     <- checkSize(convId, size, mime, message, handler)
      _ <- if (shouldSend) sync.postMessage(message.id, convId, message.editTime) else Future.successful(())
    } yield Option(message)
  }

  override def sendMessage(convId: ConvId, jpegData: Array[Byte]) =
    sendImageMessage(convId, ImageAssetFactory.getImageAsset(jpegData))

  override def sendMessage(convId: ConvId, imageAsset: ImageAsset) =
    sendImageMessage(convId, imageAsset)

  override def updateMessage(convId: ConvId, id: MessageId, text: SensitiveString) = {
    verbose(s"updateMessage($convId, $id, $text")
    messagesContent.updateMessage(id) {
      case m if m.convId == convId && m.userId == selfUserId =>
        val (tpe, ct) = MessageData.messageContent(text, weblinkEnabled = true)
        info(s"updated content: ${(tpe, ct)}")
        m.copy(msgType = tpe, content = ct, protos = Seq(GenericMessage(Uid(), MsgEdit(id, GenericContent.Text(text)))), state = Message.Status.PENDING, editTime = (m.time max m.editTime).plus(1.millis) max Instant.now)
      case m =>
//        warn(s"Can not update msg: $m") //TODO reintroduce when ids can be obfuscated
        m
    } flatMap {
      case Some(m) => sync.postMessage(m.id, m.convId, m.editTime) map { _ => Some(m) } // using PostMessage sync request to use the same logic for failures and retrying
      case None => Future successful None
    }
  }

  override def deleteMessage(convId: ConvId, id: MessageId): Future[Unit] = for {
    _ <- messagesContent.deleteOnUserRequest(Seq(id))
    _ <- sync.postDeleted(convId, id)
  } yield ()

  override def recallMessage(convId: ConvId, id: MessageId): Future[Option[MessageData]] =
    messages.recallMessage(convId, id, selfUserId) flatMap {
      case Some(msg) =>
        sync.postRecalled(convId, msg.id, id) map { _ => Some(msg) }
      case None =>
//        warn(s"could not recall message $convId, $id") //TODO reintroduce when ids can be obfuscated
        Future successful None
    }

  private def updateLastRead(msg: MessageData) = convsContent.updateConversationLastRead(msg.convId, msg.time)

  override def setConversationArchived(id: ConvId, archived: Boolean): Future[Option[ConversationData]] = convs.setConversationArchived(id, archived)

  override def setConversationMuted(id: ConvId, muted: Boolean): Future[Option[ConversationData]] =
    convsContent.updateConversationMuted(id, muted) map {
      case Some((_, conv)) => sync.postConversationState(id, ConversationState(muted = Some(conv.muted), muteTime = Some(conv.muteTime))); Some(conv)
      case None => None
    }

  override def setConversationName(id: ConvId, name: Name): Future[Option[ConversationData]] = {
    verbose(s"setConversationName($id, $name)")
    convsContent.updateConversationName(id, name) flatMap {
      case Some((_, conv)) if conv.name.contains(name) =>
        sync.postConversationName(id, conv.name.getOrElse(""))
        messages.addRenameConversationMessage(id, selfUserId, name) map (_ => Some(conv))
      case conv =>
//        warn(s"Conversation name could not be changed for: $id, conv: $conv") //TODO reintroduce when ids can be obfuscated
        CancellableFuture.successful(None)
    }
  }

  override def addConversationMembers(conv: ConvId, users: Set[UserId]) = {
    (for {
      true   <- canModifyMembers(conv)
      added  <- members.add(conv, users) if added.nonEmpty
      _      <- messages.addMemberJoinMessage(conv, selfUserId, added.map(_.userId))
      syncId <- sync.postConversationMemberJoin(conv, added.map(_.userId).toSeq)
    } yield Option(syncId))
      .recover {
        case NonFatal(e) =>
//          warn(s"Failed to add members: $users to conv: $conv", e) TODO reintroduce when ids can be obfuscated
          Option.empty[SyncId]
      }
  }

  override def removeConversationMember(conv: ConvId, user: UserId) = {
    (for {
      true    <- canModifyMembers(conv)
      Some(_) <- members.remove(conv, user)
      _       <- messages.addMemberLeaveMessage(conv, selfUserId, user)
      syncId  <- sync.postConversationMemberLeave(conv, user)
    } yield Some(syncId))
      .recover {
        case NonFatal(e) =>
//          warn(s"Failed to remove member: $user from conv: $conv", e)
          Option.empty[SyncId]
      }
  }

  private def canModifyMembers(conv: ConvId) =
    for {
      selfActive <- members.isActiveMember(conv, selfUserId)
      isGroup    <- convs.isGroupConversation(conv)
    } yield selfActive && isGroup

  override def leaveConversation(conv: ConvId) = {
    verbose(s"leaveConversation($conv)")
    for {
      _ <- convsContent.setConvActive(conv, active = false)
      _ <- removeConversationMember(conv, selfUserId)
      _ <- convsContent.updateConversationArchived(conv, archived = true)
    } yield {}
  }

  def isAbleToModifyMembers(conv: ConvId, user: UserId): Future[Boolean] = {
    val isGroup = convs.isGroupConversation(conv)
    val isActiveMember = members.isActiveMember(conv, user)
    for {
      p1 <- isGroup
      p2 <- isActiveMember
    } yield p1 && p2
  }

  override def clearConversation(id: ConvId): Future[Option[ConversationData]] = convsContent.convById(id) flatMap {
    case Some(conv) if conv.convType == ConversationType.Group || conv.convType == ConversationType.OneToOne =>
      verbose(s"clearConversation($conv)")

      convsContent.updateConversationCleared(conv.id, conv.lastEventTime) flatMap {
        case Some((_, c)) =>
          for {
            _ <- convsContent.updateConversationLastRead(c.id, c.cleared.getOrElse(Instant.EPOCH))
            _ <- convsContent.updateConversationArchived(c.id, archived = true)
            _ <- c.cleared.fold(Future.successful({}))(sync.postCleared(c.id, _).map(_ => ()))
          } yield Some(c)
        case None =>
          info("updateConversationCleared did nothing - already cleared")
          Future successful None
      }
    case Some(conv) =>
      warn(s"conversation of type ${conv.convType} can not be cleared")
      Future successful None
    case None =>
//      warn(s"conversation to be cleared not found: $id") TODO reintroduce when ids can be obfuscated
      Future successful None
  }

  override def getOrCreateOneToOneConversation(other: UserId) = {

    def createReal1to1() =
      convsContent.convById(ConvId(other.str)) flatMap {
        case Some(conv) => Future.successful(conv)
        case _ => usersStorage.get(other).flatMap {
          case Some(u) if u.connection == ConnectionStatus.Ignored =>
            for {
              conv <- convsContent.createConversationWithMembers(ConvId(other.str), u.conversation.getOrElse(RConvId()), ConversationType.Incoming, other, Set(selfUserId), hidden = true)
              _ <- messages.addMemberJoinMessage(conv.id, other, Set(selfUserId), firstMessage = true)
              _ <- u.connectionMessage.fold(Future.successful(conv))(messages.addConnectRequestMessage(conv.id, other, selfUserId, _, u.name).map(_ => conv))
            } yield conv
          case _ =>
            for {
              _ <- sync.postConversation(ConvId(other.str), Set(other), None, None, Set(Access.PRIVATE), AccessRole.PRIVATE)
              conv <- convsContent.createConversationWithMembers(ConvId(other.str), RConvId(), ConversationType.OneToOne, selfUserId, Set(other))
              _ <- messages.addMemberJoinMessage(conv.id, selfUserId, Set(other), firstMessage = true)
            } yield conv
        }
      }

    def createFake1To1(tId: TeamId) = {
      verbose(s"Checking for 1:1 conversation with user: $other")
      (for {
        allConvs <- this.members.getByUsers(Set(other)).map(_.map(_.convId))
        allMembers <- this.members.getByConvs(allConvs.toSet).map(_.map(m => m.convId -> m.userId))
        onlyUs = allMembers.groupBy { case (c, _) => c }.map { case (cid, us) => cid -> us.map(_._2).toSet }.collect { case (c, us) if us == Set(other, selfUserId) => c }
        data <- convStorage.getAll(onlyUs).map(_.flatten)
        _ = verbose(s"Found ${data.size} convs with other user: $other")
      } yield data.filter(c => c.team.contains(tId) && c.name.isEmpty)).flatMap { convs =>
        if (convs.isEmpty) {
          verbose(s"No conversation with user $other found, creating new team 1:1 conversation (type == Group)")
          createAndPostConversation(ConvId(), None, Set(other)).map(_._1)
        } else {
          if (convs.size > 1) warn(s"Found ${convs.size} available team conversations with user: $other, returning first conversation found")
          Future.successful(convs.head)
        }
      }
    }

    teamId match {
      case Some(tId) =>
        for {
          user <- usersStorage.get(other)
          conv <- if (user.exists(_.isGuest(tId))) createReal1to1() else createFake1To1(tId)
        } yield conv
      case None => createReal1to1()
    }
  }

  override def createGroupConversation(name: Option[Name] = None, members: Set[UserId] = Set.empty, teamOnly: Boolean = false) =
    createAndPostConversation(ConvId(), name, members, teamOnly)

  private def createAndPostConversation(id: ConvId, name: Option[Name], members: Set[UserId], teamOnly: Boolean = false) = {
    val (ac, ar) = getAccessAndRoleForGroupConv(teamOnly, teamId)
    for {
      conv <- convsContent.createConversationWithMembers(id, generateTempConversationId(members + selfUserId), ConversationType.Group, selfUserId, members, name, access = ac, accessRole = ar)
      _    = verbose(s"created: $conv")
      _    <- messages.addConversationStartMessage(conv.id, selfUserId, members, name)
      syncId <- sync.postConversation(id, members, conv.name, teamId, ac, ar)
    } yield (conv, syncId)
  }

  override def findGroupConversations(prefix: SearchKey, limit: Int, handleOnly: Boolean): Future[Seq[ConversationData]] =
    convStorage.search(prefix, selfUserId, handleOnly).map(_.sortBy(_.displayName.str)(currentLocaleOrdering).take(limit))

  override def knock(id: ConvId): Future[Option[MessageData]] = for {
    msg <- messages.addKnockMessage(id, selfUserId)
    _   <- sync.postMessage(msg.id, id, msg.editTime)
  } yield Some(msg)

  override def setLastRead(convId: ConvId, msg: MessageData): Future[Option[ConversationData]] =
    convsContent.updateConversationLastRead(convId, msg.time) map {
      case Some((_, conv)) =>
        sync.postLastRead(convId, conv.lastRead)
        Some(conv)
      case _ => None
    }

  override def setEphemeral(id: ConvId, expiration: Option[FiniteDuration]) = {
    convStorage.update(id, _.copy(localEphemeral = expiration)).map(_ => {})
  }

  override def setEphemeralGlobal(id: ConvId, expiration: Option[FiniteDuration]) =
    for {
      Some(conv) <- convsContent.convById(id) if conv.globalEphemeral != expiration
      resp       <- client.postMessageTimer(conv.remoteId, expiration).future
      _          <- resp.mapFuture(_ => convStorage.update(id, _.copy(globalEphemeral = expiration)))
      _          <- resp.mapFuture(_ => messages.addTimerChangedMessage(id, selfUserId, expiration))
    } yield resp

  private def mentionsMap(us: Set[UserId]): Future[Map[UserId, Name]] =
    users.getUsers(us.toSeq) map { uss =>
      uss.map(u => u.id -> u.getDisplayName)(breakOut)
    }

  private def sendImageMessage(conv: ConvId, img: api.ImageAsset) = {
    verbose(s"sendImageMessage($img, $conv)")
    for {
      data <- assets.addImageAsset(img)
      msg <- messages.addAssetMessage(conv, data)
      _ <- updateLastRead(msg)
      _ <- Future.successful(tracking.assetContribution(data.id, selfUserId))
      _ <- sync.postMessage(msg.id, conv, msg.editTime)
    } yield Some(msg)
  }

  private def sendAssetMessage(in: AssetForUpload, conv: ConversationData, handler: ErrorHandler): Future[Option[MessageData]] =
    for {
      mime <- in.mimeType
      asset <- assets.addAsset(in, conv.remoteId)
      message <- messages.addAssetMessage(conv.id, asset)
      _ <- updateLastRead(message)
      size <- in.sizeInBytes
      _ <- Future.successful(tracking.assetContribution(asset.id, selfUserId))
      shouldSend <- checkSize(conv.id, size, mime, message, handler)
      _ <- if (shouldSend) sync.postMessage(message.id, conv.id, message.editTime) else Future.successful(())
    } yield Some(message)

  def isFileTooLarge(size: Long, mime: Mime) = mime match {
    case Mime.Video() => false
    case _ => size > AssetData.MaxAllowedAssetSizeInBytes
  }

  private def shouldWarnAboutFileSize(size: Long) =
    if (size < LargeAssetWarningThresholdInBytes) Future successful None
    else for {
      mode         <- network.networkMode.head
      inForeground <- accounts.accountState(selfUserId).map(_ == InForeground).head
    } yield {
      (mode, inForeground) match {
        case (OFFLINE | WIFI, _) => None
        case (net, true) => Some(net)
        case _ => None
      }
    }

  private def showLargeFileWarning(convId: ConvId, size: Long, mime: Mime, net: NetworkMode, message: MessageData, handler: ErrorHandler): Unit = {
    Threading.assertUiThread()

    handler.noWifiAndFileIsLarge(size, net, new api.MessageContent.Asset.Answer {
      override def ok(): Unit = messages.retryMessageSending(convId, message.id)

      override def cancel(): Unit = messagesContent.deleteMessage(message).map(_ => assetUploadCancelled ! mime)
    })
  }

  private def checkSize(convId: ConvId, size: Option[Long], mime: Mime, message: MessageData, handler: ErrorHandler) = size match {
    case None => Future successful true
    case Some(s) if isFileTooLarge(s, mime) =>
      for {
        _ <- messages.updateMessageState(convId, message.id, Message.Status.FAILED)
        _ <- errors.addAssetTooLargeError(convId, message.id)
        _ <- Future.successful(assetUploadFailed ! ErrorResponse.internalError("asset too large"))
      } yield false
    case Some(s) =>
      shouldWarnAboutFileSize(s) flatMap {
        case Some(net) =>
          // will mark message as failed and ask user if it should really be sent
          // marking as failed ensures that user has a way to retry even if he doesn't respond to this warning
          // this is possible if app is paused or killed in meantime, we don't want to be left with message in state PENDING without a sync request
          messages.updateMessageState(convId, message.id, Message.Status.FAILED).map { _ =>
            showLargeFileWarning(convId, s, mime, net, message, handler)
            false
          }(Threading.Ui)
        case _ =>
          Future successful true
      }
  }

}

object ConversationsUiService {
  val LargeAssetWarningThresholdInBytes = 3145728L // 3MiB
}
