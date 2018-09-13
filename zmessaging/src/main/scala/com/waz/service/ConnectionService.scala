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
package com.waz.service

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.IConversation.{Access, AccessRole}
import com.waz.content._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.ConnectionService._
import com.waz.service.conversation.{ConversationsContentUpdater, NameUpdater}
import com.waz.service.messages.MessagesService
import com.waz.service.push.PushService
import com.waz.sync.SyncServiceHandle
import com.waz.threading.Threading
import com.waz.utils.events.EventContext
import com.waz.utils.{RichFuture, RichWireInstant, Serialized, RichIterable}

import scala.collection.breakOut
import scala.concurrent.Future

trait ConnectionService {
  def connectToUser(userId: UserId, message: String, name: String): Future[Option[ConversationData]]
  def handleUserConnectionEvents(events: Seq[UserConnectionEvent]): Future[Unit]
}

class ConnectionServiceImpl(selfUserId:      UserId,
                            teamId:          Option[TeamId],
                            push:            PushService,
                            convsContent:    ConversationsContentUpdater,
                            convsStorage:    ConversationStorage,
                            members:         MembersStorage,
                            messages:        MessagesService,
                            messagesStorage: MessagesStorage,
                            users:           UserService,
                            usersStorage:    UsersStorage,
                            sync:            SyncServiceHandle) extends ConnectionService {

  import Threading.Implicits.Background
  private implicit val ec = EventContext.Global

  val connectionEventsStage = EventScheduler.Stage[UserConnectionEvent]((c, e) => handleUserConnectionEvents(e))

  val contactJoinEventsStage = EventScheduler.Stage[ContactJoinEvent] { (c, es) =>
    RichFuture.processSequential(es) { e =>
      users.getOrCreateUser(e.user) flatMap { _ =>
        // update user name if it was just created (has empty name)
        users.updateUserData(e.user, u => u.copy(name = if (u.name == "") e.name else u.name))
      }
    }
  }

  override def handleUserConnectionEvents(events: Seq[UserConnectionEvent]) = {
    verbose(s"handleUserConnectionEvents: ${events.log}")

    import ConnectionStatus._

    val lastEvents = events.groupBy(_.to).map { case (to, es) => to -> es.maxBy(_.lastUpdated) }
    val fromSync: Set[UserId] = lastEvents.filter(_._2.localTime == LocalInstant.Epoch).map(_._2.to)(breakOut)
    verbose(s"lastEvents: $lastEvents, fromSync: $fromSync")

    def updateOrCreate(id: UserId, user: Option[UserData]): UserData = {
      val event = lastEvents(id)
      user match {
        case Some(old) =>
          old.copy(conversation = Some(event.convId)).updateConnectionStatus(event.status, Some(event.lastUpdated), event.message)
        case _ =>
          UserData(
            id                    = event.to,
            name                  = UserService.DefaultUserName,
            connection            = event.status,
            conversation          = Some(event.convId),
            connectionMessage     = event.message,
            searchKey             = SearchKey(UserService.DefaultUserName),
            connectionLastUpdated = event.lastUpdated)
      }
    }

    for {
      users  <- usersStorage.updateOrCreateAll2(lastEvents.map(_._2.to), { case (uId, user) => updateOrCreate(uId, user) })
      toSync = users.collect { case user if Set(Accepted, PendingFromOther, PendingFromUser).contains(user.connection) => user.id }
      _      <- sync.syncUsers(toSync)
      oneToOneConvData = users.map { user =>
        val convType = user.connection match {
          case PendingFromUser | Cancelled => ConversationType.WaitForConnection
          case PendingFromOther | Ignored  => ConversationType.Incoming
          case _ => ConversationType.OneToOne
        }
        OneToOneConvData(user.id, user.conversation, convType)
      }
      otoConvs   <- getOrCreateOneToOneConversations(oneToOneConvData.toSeq)
      _ = verbose(s"otoConvs: ${otoConvs.log}")
      convToUser = users.map(_.id).flatMap(id => otoConvs.get(id).map(c => c.id -> id)).toMap
      _          <- members.addAll(convToUser.mapValues(u => Set(u, selfUserId)))
      updatedConvs <- convsStorage.updateAll2(convToUser.keys, { conv =>
        val userId = convToUser(conv.id)
        val user   = users.find(_.id == userId).get
        conv.copy(
          convType      = otoConvs(userId).convType,
          hidden        = Set(Ignored, Blocked, Cancelled)(user.connection),
          lastEventTime = conv.lastEventTime max lastEvents(userId).lastUpdated
        )
      }).map(_.map(_._2))
      _ <- Future.sequence(updatedConvs.map { conv =>

        val userId = convToUser(conv.id)
        val user = users.find(_.id == userId).get

        messagesStorage.getLastMessage(conv.id).flatMap {
          case None if conv.convType == ConversationType.Incoming =>
            messages.addConnectRequestMessage(conv.id, user.id, selfUserId, user.connectionMessage.getOrElse(""), user.getDisplayName, fromSync = fromSync(userId))
          case None if conv.convType == ConversationType.OneToOne =>
            messages.addDeviceStartMessages(Seq(conv), selfUserId)
          case _ =>
            Future.successful(())
        }
      })
    } yield {}
  }

  /**
   * Connects to user and creates one-to-one conversation if needed. Returns existing conversation if user is already connected.
   */
  def connectToUser(userId: UserId, message: String, name: String): Future[Option[ConversationData]] = {

    def sanitizedName = if (name.isEmpty) "_" else if (name.length >= 256) name.substring(0, 256) else name

    def connectIfUnconnected() = users.getOrCreateUser(userId).flatMap { user =>
      if (user.isConnected) {
        verbose(s"User already connected: $user")
        Future.successful(None)
      } else {
        users.updateConnectionStatus(user.id, ConnectionStatus.PendingFromUser).flatMap {
          case Some(u) => sync.postConnection(userId, sanitizedName, message).map(_ => Some(u))
          case _       => Future.successful(None)
        }
      }
    }

    connectIfUnconnected().flatMap {
      case Some(_) =>
        for {
          conv <- getOrCreateOneToOneConversation(userId, convType = ConversationType.WaitForConnection)
          _ = verbose(s"connectToUser, conv: $conv")
          _ <- messages.addConnectRequestMessage(conv.id, selfUserId, userId, message, name)
        } yield Some(conv)
      case None => //already connected
        convsContent.convById(ConvId(userId.str))
    }
  }

  def acceptConnection(userId: UserId): Future[ConversationData] =
    users.updateConnectionStatus(userId, ConnectionStatus.Accepted) map {
      case Some(_) =>
        sync.postConnectionStatus(userId, ConnectionStatus.Accepted) map { syncId =>
          sync.syncConversations(Set(ConvId(userId.str)), Some(syncId))
        }
      case _ =>
    } flatMap { _ =>
      getOrCreateOneToOneConversation(userId, convType = ConversationType.OneToOne) flatMap { conv =>
        convsContent.updateConversation(conv.id, Some(ConversationType.OneToOne), hidden = Some(false)) flatMap { updated =>
          messages.addMemberJoinMessage(conv.id, selfUserId, Set(selfUserId), firstMessage = true) map { _ =>
            updated.fold(conv)(_._2)
          }
        }
      }
    }

  def ignoreConnection(userId: UserId): Future[Option[UserData]] =
    for {
      user <- users.updateConnectionStatus(userId, ConnectionStatus.Ignored)
      _    <- user.fold(Future.successful({}))(_ => sync.postConnectionStatus(userId, ConnectionStatus.Ignored).map(_ => {}))
      _    <- convsContent.hideIncomingConversation(userId)
    } yield user

  def blockConnection(userId: UserId): Future[Option[UserData]] = {
    for {
      _    <- convsContent.setConversationHidden(ConvId(userId.str), hidden = true)
      user <- users.updateConnectionStatus(userId, ConnectionStatus.Blocked)
      _    <- user.fold(Future.successful({}))(_ => sync.postConnectionStatus(userId, ConnectionStatus.Blocked).map(_ => {}))
    } yield user
  }

  def unblockConnection(userId: UserId): Future[ConversationData] =
    for {
      user <- users.updateConnectionStatus(userId, ConnectionStatus.Accepted)
      _    <- user.fold(Future.successful({})) { _ =>
        for {
          syncId <- sync.postConnectionStatus(userId, ConnectionStatus.Accepted)
          _      <- sync.syncConversations(Set(ConvId(userId.str)), Some(syncId)) // sync conversation after syncing connection state (conv is locked on backend while connection is blocked) TODO: we could use some better api for that
        } yield {}
      }
      conv    <- getOrCreateOneToOneConversation(userId, convType = ConversationType.OneToOne)
      updated <- convsContent.updateConversation(conv.id, Some(ConversationType.OneToOne), hidden = Some(false)) map { _.fold(conv)(_._2) } // TODO: what about messages
    } yield updated


  def cancelConnection(userId: UserId): Future[Option[UserData]] = {
    users.updateUserData(userId, { user =>
      if (user.connection == ConnectionStatus.PendingFromUser) user.copy(connection = ConnectionStatus.Cancelled)
      else {
        warn(s"can't cancel connection for user in wrong state: $user")
        user
      }
    }) flatMap {
      case Some((prev, user)) if prev != user =>
        sync.postConnectionStatus(userId, ConnectionStatus.Cancelled)
        convsContent.setConversationHidden(ConvId(user.id.str), hidden = true) map { _ => Some(user) }
      case None => Future successful None
    }
  }

  /**
    * Finds or creates local one-to-one conversation with given user and/or remoteId.
    * Updates remoteId if different and merges conversations if two are found for given local and remote id.
    *
    * Will not post created conversation to backend.
    * TODO: improve - it looks too complicated and duplicates some code
    */
  private def getOrCreateOneToOneConversation(toUser: UserId, remoteId: Option[RConvId] = None, convType: ConversationType = ConversationType.OneToOne): Future[ConversationData] =
    getOrCreateOneToOneConversations(Seq(OneToOneConvData(toUser, remoteId, convType))).map(_.values.head)

  private def getOrCreateOneToOneConversations(convsInfo: Seq[OneToOneConvData]): Future[Map[UserId, ConversationData]] =
    Serialized.future('getOrCreateOneToOneConversations) {
      verbose(s"getOrCreateOneToOneConversations: convs: ${convsInfo.log}")

      def convIdForUser(userId: UserId) = ConvId(userId.str)
      def userIdForConv(convId: ConvId) = UserId(convId.str)

      for {
        allUsers <- usersStorage.listAll(convsInfo.map(_.toUser))
        userMap   = allUsers.map(u => u.id -> Vector(u)).toMap
        remoteIds = convsInfo.map(i => convIdForUser(i.toUser) -> i.remoteId).toMap
        newConvs  = convsInfo.map {
          case OneToOneConvData(toUser, remoteId, convType) =>
            val convId = convIdForUser(toUser)
            convId -> ConversationData(
              convId,
              remoteId.getOrElse(RConvId(toUser.str)),
              name          = None,
              creator       = selfUserId,
              convType      = convType,
              generatedName = NameUpdater.generatedName(convType)(userMap(toUser)),
              team          = teamId,
              access        = Set(Access.PRIVATE),
              accessRole    = Some(AccessRole.PRIVATE)
            )
        }.toMap

        remotes <- convsStorage.getByRemoteIds2(convsInfo.flatMap(_.remoteId).toSet)

        _ <- convsStorage.updateLocalIds(convsInfo.collect {
          case OneToOneConvData(toUser, Some(remoteId), _) if remotes.contains(remoteId) =>
            remotes(remoteId).id -> convIdForUser(toUser)
        }.toMap)

        // remotes need to be refreshed after updating local ids
        remotes <- convsStorage.getByRemoteIds2(convsInfo.flatMap(_.remoteId).toSet)

        result <- convsStorage.updateOrCreateAll2(newConvs.keys, {
          case (cId, Some(conv)) =>
            remoteIds(cId).fold(conv)(rId => remotes.getOrElse(rId, conv.copy(remoteId = rId)))
          case (cId, _) =>
            val newConv = newConvs(cId)
            newConv
        })
      } yield result.map(conv => userIdForConv(conv.id) -> conv).toMap
    }

}

object ConnectionService {

  case class OneToOneConvData(toUser: UserId, remoteId: Option[RConvId] = None, convType: ConversationType = ConversationType.OneToOne)
}