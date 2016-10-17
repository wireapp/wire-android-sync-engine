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

import android.content.Context
import com.softwaremill.macwire._
import com.waz.Analytics
import com.waz.ZLog._
import com.waz.api.ErrorType
import com.waz.api.Verification._
import com.waz.api.impl.ErrorResponse
import com.waz.content._
import com.waz.model.ConversationData.{ConversationStatus, ConversationType}
import com.waz.model._
import com.waz.service._
import com.waz.service.assets.AssetService
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.service.push.PushServiceSignals
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.ConversationsClient.ConversationResponse
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.utils.events.EventContext
import org.threeten.bp.Instant

import scala.collection.{breakOut, mutable}
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.control.NoStackTrace

class ConversationsService(context: Context, push: PushServiceSignals, users: UserService, usersStorage: UsersStorage,
                           messagesStorage: MessagesStorage, membersStorage: MembersStorage,
                           convsStorage: ConversationStorage, val content: ConversationsContentUpdater, listState: ConversationsListStateService,
                           sync: SyncServiceHandle, errors: ErrorsService,
                           messages: MessagesService, assets: AssetService, storage: ZmsDatabase,
                           msgContent: MessagesContentUpdater, kvService: KeyValueStorage, eventScheduler: => EventScheduler) {

  private implicit val tag: LogTag = logTagFor[ConversationsService]
  private implicit val ev = EventContext.Global
  import Threading.Implicits.Background
  import content._
  import messages._
  import users._

  private val nameUpdater = wire[NameUpdater]
  nameUpdater.registerForUpdates()

  val convStateEventProcessingStage = EventScheduler.Stage[ConversationStateEvent] { (convId, events) =>
    withSelfUserFuture { selfUserId =>
      RichFuture.processSequential(events)(processConversationEvent(_, selfUserId))
    }
  }

  push.onSlowSyncNeeded { req =>
    verbose(s"onSlowSyncNeeded($req)")

    for {
      _ <- if (req.lostHistory) onHistoryLost() else successful(())
      _ <- scheduleSlowSync()
    } yield ()
  }

  errors.onErrorDismissed {
    case ErrorData(_, ErrorType.CANNOT_CREATE_GROUP_CONVERSATION_WITH_UNCONNECTED_USER, _, _, Some(convId), _, _, _, _) =>
      deleteConversation(convId)
    case ErrorData(_, ErrorType.CANNOT_ADD_UNCONNECTED_USER_TO_CONVERSATION, userIds, _, Some(convId), _, _, _, _) => Future.successful(())
    case ErrorData(_, ErrorType.CANNOT_ADD_USER_TO_FULL_CONVERSATION, userIds, _, Some(convId), _, _, _, _) => Future.successful(())
    case ErrorData(_, ErrorType.CANNOT_SEND_MESSAGE_TO_UNVERIFIED_CONVERSATION, _, _, Some(conv), _, _, _, _) =>
      convsStorage.update(conv, { c => c.copy(verified = if (c.verified == UNVERIFIED) UNKNOWN else c.verified) })
  }

  private def scheduleSlowSync() =
    getSelfUserId flatMap {
      case Some(id) => sync.syncConversations()
      case None     => sync.syncSelfUser() flatMap (dependency => sync.syncConversations(Some(dependency)))
    }

  // TODO: this is just very basic implementation creating empty message
  // This should be updated to include information about possibly missed changes
  // this message will be shown rarely (when notifications stream skips data)
  private def onHistoryLost() =
    users.withSelfUserFuture { selfUserId =>
      convsStorage.list flatMap { messages.addHistoryLostMessages(_, selfUserId) }
    }

  def processConversationEvent(ev: ConversationStateEvent, selfUserId: UserId, retryCount: Int = 0): Future[Any] = ev match {
    case CreateConversationEvent(id, rConvId, time, from, data) =>
      updateConversations(selfUserId, Seq(data)) flatMap { case (_, created) => Future.traverse(created) (created =>
        addMemberJoinMessage(created.id, from, data.members.map(_.userId).toSet)
      )}

    case ConversationEvent(_, rConvId, _, _) =>
      convByRemoteId(rConvId) flatMap {
        case Some(conv) => processUpdateEvent(conv, ev)
        case None if retryCount > 3 =>
          Analytics.saveException(new Exception("No conversation data found for event") with NoStackTrace, s"event: $ev")
          successful(())
        case None =>
          ev match {
            case MemberJoinEvent(_, _, time, from, members, _) if from != selfUserId =>
              // this happens when we are added to group conversation
              createGroupConversationOnMemberJoin(rConvId, time.instant, from, members)
            case _ =>
              warn(s"No conversation data found for event: $ev on try: $retryCount")
              processConvWithRemoteId(rConvId, retryAsync = true) { processUpdateEvent(_, ev) }
          }
      }
  }

  private def processUpdateEvent(conv: ConversationData, ev: ConversationEvent): Future[Any] = ev match {
    case RenameConversationEvent(_, _, time, _, name) => updateConversationName(conv.id, name, Some(time.instant))

    case MemberJoinEvent(id, convId, time, from, userIds, _) =>
      def joined(updated: ConversationData) = ! conv.activeMember && updated.activeMember && updated.convType == ConversationType.Group

      def ensureConvActive() = updateConversationStatus(conv.id, ConversationStatus.Active, time.instant).map(_.map(_._2).filter(joined))
        .flatMapSome(_ => sync.syncCallState(conv.id, fromFreshNotification = false).map(Some(_)))

      for {
        _        <- users.syncNotExistingOrExpired(userIds)
        _        <- membersStorage.add(conv.id, time.instant, userIds: _*)
        selfUser <- selfUserOrFail
        _        <- if (userIds.contains(selfUser)) ensureConvActive() else successful(None)
      } yield ()

    case MemberLeaveEvent(id, convId, time, from, userIds) =>
      membersStorage.remove(conv.id, time.instant, userIds: _*) flatMap { _ =>
        withSelfUserFuture { selfUserId =>
          if (userIds.contains(selfUserId)) updateConversationStatus(conv.id, ConversationStatus.Inactive, time.instant)
          else successful(())
        }
      }

    case MemberUpdateEvent(id, convId, time, from, state) => updateConversationState(conv.id, state)

    case ConnectRequestEvent(_, _, time, from, _, recipient, _, _) =>
      debug(s"ConnectRequestEvent(from = $from, recipient = $recipient")
      membersStorage.add(conv.id, time.instant, from, recipient) flatMap { added =>
        val userIdsAdded = added map (_.userId)
        usersStorage.listAll(userIdsAdded) map { localUsers =>
          syncIfNeeded(localUsers: _*)
          sync.syncUsersIfNotEmpty(userIdsAdded.filterNot(id => localUsers exists (_.id == id)).toSeq)
        }
      }

    case _ => successful(())
  }

  private def createGroupConversationOnMemberJoin(remoteId: RConvId, time: Instant, from: UserId, members: Seq[UserId]) = {
    insertConversation(ConversationData(ConvId(), remoteId, None, from, ConversationType.Group, lastEventTime = time)) flatMap { conv =>
      membersStorage.add(conv.id, time, from +: members: _*) flatMap { ms =>
        addMemberJoinMessage(conv.id, from, members.toSet) map { _ =>
          sync.syncConversation(conv.id) flatMap { _ => sync.syncCallState(conv.id, fromFreshNotification = false) }
          conv
        }
      }
    }
  }

  def getSelfConversation: Future[Option[ConversationData]] = getSelfUserId flatMap {
    case Some(selfId) =>
      val selfConvId = ConvId(selfId.str)

      def generateSelfConv(id: UserId) = usersStorage.get(id) map {
        case Some(user) => ConversationData(ConvId(id.str), RConvId(id.str), None, id, ConversationType.Self, generatedName = user.name)
        case _ => ConversationData(ConvId(id.str), RConvId(id.str), None, id, ConversationType.Self)
      }

      convById(selfConvId) flatMap {
        case Some(c) => successful(Some(c))
        case _ => generateSelfConv(selfId).flatMap(conv => convsStorage.getOrCreate(selfConvId, conv).map(Some(_)))
      }
    case _ => successful(None)
  }

  def updateConversations(conversations: Seq[ConversationResponse]): Future[Seq[ConversationData]] =
    withSelfUserFuture { selfUserId =>
      Future.traverse(conversations) { conv =>
        eventScheduler.post(conv.conversation.remoteId) {
          updateConversations(selfUserId, Seq(conv)) flatMap { case (all, created) =>
            messages.addDeviceStartMessages(created, selfUserId) map (_ => all)
          }
        }
      }.map(_.foldLeft(Vector.empty[ConversationData])(_ ++ _))
    }


  private def updateConversations(selfUserId: UserId, convs: Seq[ConversationResponse]): Future[(Seq[ConversationData], Seq[ConversationData])] = {

    def updateConversationData() = {
      def createOneToOne(conv: ConversationData, members: Seq[ConversationMemberData]) =
        if (members.size > 1) conv.copy(id = oneToOneLocalId(members, selfUserId)) else conv.copy(hidden = true)

      def oneToOneLocalId(members: Seq[ConversationMemberData], selfUserId: UserId) = members.find(_.userId != selfUserId).fold(ConvId())(m => ConvId(m.userId.str))

      def findExistingId = convsStorage { (convById, remoteMap) =>
        def byRemoteId(id: RConvId) = returning(remoteMap.get(id).flatMap(convById.get)) { res => verbose(s"byRemoteId($id) - $res")}

        convs map { case cr @ ConversationResponse(conv, members) =>
          val newId = if (ConversationType.isOneToOne(conv.convType)) oneToOneLocalId(members, selfUserId) else conv.id

          val matching = byRemoteId(conv.remoteId).orElse {
            convById.get(newId) orElse {
              if (ConversationType.isOneToOne(conv.convType)) None
              else byRemoteId(ConversationsService.generateTempConversationId((members.map(_.userId) :+ selfUserId).distinct: _*))
            }
          }

          (conv.copy(id = matching.fold(newId)(_.id)), members)
        }
      }

      var created = new mutable.HashSet[ConversationData]

      def updater(conv: ConversationData, members: Seq[ConversationMemberData]): (Option[ConversationData] => ConversationData) = {
        case Some(old) => old.updated(conv).getOrElse(old)
        case None if ConversationType.isOneToOne(conv.convType) => returning(createOneToOne(conv, members))(created += _)
        case None =>
          created += conv
          conv
      }

      for {
        withId <- findExistingId
        convs  <- convsStorage.updateOrCreateAll(withId.map { case (conv, members) => conv.id -> updater(conv, members) } (breakOut))
      } yield (convs, created.toSeq)
    }

    def updateMembers() = Future.sequence(convs map {
        case ConversationResponse(conv, members) =>
          convByRemoteId(conv.remoteId) flatMap {
            case Some(c) => membersStorage.set(c.id, conv.lastEventTime, selfUserId +: members.filter(_.active).map(_.userId))
            case _ =>
              error(s"updateMembers() didn't find conv with given remote id for: $conv")
              successful(())
          }
      })

    def syncUsers() = syncNotExistingOrExpired(convs.flatMap { case ConversationResponse(_, members) => members.map(_.userId) })

    for {
      (convs, created) <- updateConversationData()
      _                <- updateMembers()
      _                <- syncUsers()
    } yield
      (convs.toSeq, created)
  }

  def setConversationArchived(id: ConvId, archived: Boolean): Future[Option[ConversationData]] =
    updateConversationArchived(id, archived) flatMap {
      case Some((_, conv)) =>
        sync.postConversationState(id, ConversationState(archived = Some(conv.archived), archiveTime = Some(conv.archiveTime))) map { _ => Some(conv) }
      case None =>
        Future successful None
    }

  private def deleteConversation(convId: ConvId) = for {
    _ <- convsStorage.remove(convId)
    _ <- membersStorage.delete(convId)
    _ <- msgContent.deleteMessagesForConversation(convId: ConvId)
  } yield ()
  
  def forceNameUpdate(id: ConvId) = {
    warn(s"forceNameUpdate($id)")
    nameUpdater.forceNameUpdate(id)
  }

  def onMemberAddFailed(conv: ConvId, users: Seq[UserId], error: ErrorType, resp: ErrorResponse) = for {
    _ <- errors.addErrorWhenActive(ErrorData(error, resp, conv, users))
    _ <- membersStorage.remove(conv, users: _*)
    _ <- removeLocalMemberJoinMessage(conv, users.toSet)
  } yield ()
}

object ConversationsService {
  import scala.concurrent.duration._

  val RetryBackoff = new ExponentialBackoff(500.millis, 3.seconds)

  /**
   * Generate temp ConversationID to identify conversations which don't have a RConvId yet
   */
  def generateTempConversationId(users: UserId *) =
    RConvId(users.map(_.toString).sorted.foldLeft("")(_ + _))
}
