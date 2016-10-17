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

import com.waz.Analytics
import com.waz.ZLog._
import com.waz.content._
import com.waz.model.ConversationData.{ConversationStatus, ConversationType}
import com.waz.model._
import com.waz.service.UserService
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils._
import com.waz.utils.events.Signal
import org.threeten.bp.Instant

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

class ConversationsContentUpdater(val storage: ConversationStorage, users: UserService, membersStorage: MembersStorage, messagesStorage: => MessagesStorage) {
  import com.waz.utils.events.EventContext.Implicits.global
  private implicit val tag: LogTag = logTagFor[ConversationsContentUpdater]
  private implicit val dispatcher = new SerialDispatchQueue(name = "ConversationContentUpdater")

  val conversationsSignal: Signal[ConversationsSet] = storage.convsSignal

  val conversationsFuture = Future successful storage

  storage.convUpdated { case (prev, conv) =>
    if (prev.cleared != conv.cleared) {
      verbose(s"cleared updated will clear messages, prev: $prev, updated: $conv")
      messagesStorage.clear(conv.id, conv.cleared).recoverWithLog()
    }
  }

  def convById(id: ConvId): Future[Option[ConversationData]] = storage.get(id)

  def convByRemoteId(id: RConvId): Future[Option[ConversationData]] = storage.getByRemoteId(id)

  def isGroupConversation(id: ConvId) = convById(id).map(_.exists(_.convType == ConversationType.Group))

  def insertConversation(conv: ConversationData) = storage.insert(conv)

  def updateConversationName(id: ConvId, name: String, time: Option[Instant] = None) = storage.update(id, { conv =>
      if (conv.convType == ConversationType.Group && time.forall(_ >= conv.renameEvent))
        conv.copy(name = if (name.isEmpty) None else Some(name), renameEvent = time.getOrElse(conv.renameEvent))
      else
        conv
    })

  def setConversationStatusInactive(id: ConvId) = storage.update(id, _.copy(status = ConversationStatus.Inactive.value))

  def updateConversationStatus(id: ConvId, status: ConversationStatus, time: Instant) = storage.update(id, { c =>
    if (time.isAfter(c.statusTime)) c.copy(status = status.value, statusTime = time) else c
  })

  def updateConversationArchived(id: ConvId, archived: Boolean) = storage.update(id, { c =>
    c.copy(archived = archived, archiveTime = c.lastEventTime)
  })

  def updateConversationMuted(conv: ConvId, muted: Boolean) = storage.update(conv, { c =>
    c.copy(muted = muted, muteTime = c.lastEventTime)
  })

  def updateConversationLastRead(id: ConvId, time: Instant) = storage.update(id, { conv =>
    verbose(s"updateConversationLastRead($id, $time)")
    conv.withLastRead(time)
  })

  def updateConversationCleared(id: ConvId, time: Instant) = storage.update(id, { conv =>
    verbose(s"updateConversationCleared($id, $time)")
    conv.withCleared(time).withLastRead(time)
  })

  def updateConversationState(id: ConvId, state: ConversationState) = storage.update(id, { conv =>
    verbose(s"updateConversationState($conv, state: $state)")

    val (archived, archiveTime) = state match {
      case ConversationState(Some(a), Some(t), _, _) if t >= conv.archiveTime => (a, t)
      case _ => (conv.archived, conv.archiveTime)
    }

    val (muted, muteTime) = state match {
      case ConversationState(_, _, Some(m), Some(t)) if t >= conv.muteTime => (m, t)
      case _ => (conv.muted, conv.muteTime)
    }

    conv.copy(archived = archived, archiveTime = archiveTime, muted = muted, muteTime = muteTime)
  })

  def updateLastEvent(id: ConvId, time: Instant) = storage.update(id, { conv =>
    verbose(s"updateLastEvent($conv, $time)")

    if (conv.lastEventTime.isAfter(time)) conv
    else {
      debug(s"updating: $conv, lastEventTime: $time")
      conv.copy(lastEventTime = conv.lastEventTime max time)
    }
  })

  def updateConversation(id: ConvId, convType: Option[ConversationType] = None, hidden: Option[Boolean] = None) =
    storage.update(id, { conv =>
      if (convType.forall(_ == conv.convType) && hidden.forall(_ == conv.hidden)) conv
      else conv.copy(convType = convType.getOrElse(conv.convType), hidden = hidden.getOrElse(conv.hidden))
    })

  def hideConversation(id: ConvId) = updateConversationHidden(id, hidden = true)

  def unhideConversation(id: ConvId) = updateConversationHidden(id, hidden = false)

  private def updateConversationHidden(id: ConvId, hidden: Boolean) = storage.update(id, _.copy(hidden = hidden))

  def createConversationWithMembers(convId: ConvId, remoteId: RConvId, convType: ConversationType, selfUserId: UserId, members: Seq[UserId], hidden: Boolean = false) =
    for {
      user <- users.getUsers(members)
      conv <- storage.insert(ConversationData(convId, remoteId, None, selfUserId, convType, generatedName = NameUpdater.generatedName(convType)(user), hidden = hidden))
      _    <- addConversationMembers(convId, selfUserId, members)
    } yield conv

  def addConversationMembers(convId: ConvId, selfUserId: UserId, members: Seq[UserId]) =
    membersStorage.add(convId, selfUserId +: members: _*)

  /**
   * Finds or creates local one-to-one conversation with given user and/or remoteId.
   * Updates remoteId if different and merges conversations if two are found for given local and remote id.
   *
   * Will not post created conversation to backend.
   * TODO: improve - it looks too complicated and duplicates some code
   */
  def getOneToOneConversation(toUser: UserId, selfUserId: UserId, remoteId: Option[RConvId] = None, convType: ConversationType = ConversationType.OneToOne) =
    Serialized.future(('getOneToOneConversation, toUser)) {
      verbose(s"getOneToOneConversation($toUser, self: $selfUserId, remote: $remoteId, convType: $convType")
      val convId = ConvId(toUser.str)

      def createConversation() = createConversationWithMembers(convId, remoteId.getOrElse(RConvId(toUser.str)), convType, selfUserId, Seq(toUser), false)

      storage.get(convId) flatMap { localConv =>
        if (remoteId.forall(r => localConv.exists(_.remoteId == r)))
          localConv.fold(createConversation())(Future.successful)
        else // update remote id or merge conversations
          storage.getByRemoteId(remoteId.get) flatMap { remoteConv =>
            (localConv, remoteConv) match {
              case (_, Some(remote)) =>
                if (remote.id == convId) Future.successful(remote) // means conv was created between get(convId) and getByRemoteId(remoteId.get)
                else storage.updateLocalId(remote.id, convId) map (_.get) // XXX: should we update messages and members here ?
              case (Some(_), None) => storage.update(convId, _.copy(remoteId = remoteId.get)) map (_.get._2)
              case _ => createConversation()
            }
          }
      }
    }

  def hideIncomingConversation(user: UserId) = storage.update(ConvId(user.str), { conv =>
    if (conv.convType == ConversationType.Incoming) conv.copy(hidden = true) else conv
  })

  def hideConversationOfUser(user: UserId) = storage.update(ConvId(user.str), _.copy(hidden = true))

  /**
   * Helper for event processing. Can be used whenever some event processing needs to access conversation by remoteId,
   * but conversation may not be present yet, for example if events are processed in wrong order.
   * Processing will be retried with delay.
   *
   * @param retryAsync - true if retry should be executed asynchronously, this should be used when executing from some ProcessingQueue, using delays in processing queue will block it
   */
  def processConvWithRemoteId[A](remoteId: RConvId, retryAsync: Boolean, retryCount: Int = 0)(processor: ConversationData => Future[A])(implicit tag: LogTag, ec: ExecutionContext): Future[A] = {

    def retry() = CancellableFuture.delay(ConversationsService.RetryBackoff.delay(retryCount)).future .flatMap { _ =>
      processConvWithRemoteId(remoteId, retryAsync = false, retryCount + 1)(processor)
    } (ec)

    convByRemoteId(remoteId) .flatMap {
      case Some(conv) => processor(conv)
      case None if retryCount > 3 =>
        val ex = new NoSuchElementException("No conversation data found") with NoStackTrace
        Analytics.saveException(ex, s"remoteId: $remoteId")
        Future.failed(ex)
      case None =>
        warn(s"No conversation data found for remote id: $remoteId on try: $retryCount")
        if (retryAsync) {
          retry()
          Future.failed(BoxedError(new NoSuchElementException(s"No conversation data found for: $remoteId") with NoStackTrace)) // use BoxedError to avoid sending unnecessary hockey report
        } else
          retry()
    } (ec)
  }
}
