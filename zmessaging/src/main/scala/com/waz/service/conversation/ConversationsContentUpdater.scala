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
import com.waz.api.IConversation.{Access, AccessRole}
import com.waz.content._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.{UserId, _}
import com.waz.service.UserService
import com.waz.service.tracking.TrackingService
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils._
import com.waz.utils.events.Signal
import org.threeten.bp.Instant

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

trait ConversationsContentUpdater {
  def convById(id: ConvId): Future[Option[ConversationData]]
  def convByRemoteId(id: RConvId): Future[Option[ConversationData]]
  def storage: ConversationStorage
  def getOneToOneConversation(toUser: UserId, selfUserId: UserId, remoteId: Option[RConvId] = None, convType: ConversationType = ConversationType.OneToOne): Future[ConversationData]
  def updateConversation(id: ConvId, convType: Option[ConversationType] = None, hidden: Option[Boolean] = None): Future[Option[(ConversationData, ConversationData)]]
  def hideIncomingConversation(user: UserId): Future[Option[(ConversationData, ConversationData)]]
  def setConversationHidden(id: ConvId, hidden: Boolean): Future[Option[(ConversationData, ConversationData)]]
  def processConvWithRemoteId[A](remoteId: RConvId, retryAsync: Boolean, retryCount: Int = 0)(processor: ConversationData => Future[A])(implicit tag: LogTag, ec: ExecutionContext): Future[A]
  def updateConversationLastRead(id: ConvId, time: Instant): Future[Option[(ConversationData, ConversationData)]]
  def updateConversationMuted(conv: ConvId, muted: Boolean): Future[Option[(ConversationData, ConversationData)]]
  def updateConversationName(id: ConvId, name: String): Future[Option[(ConversationData, ConversationData)]]
  def setConvActive(id: ConvId, active: Boolean): Future[Option[(ConversationData, ConversationData)]]
  def updateConversationArchived(id: ConvId, archived: Boolean): Future[Option[(ConversationData, ConversationData)]]
  def updateConversationCleared(id: ConvId, time: Instant): Future[Option[(ConversationData, ConversationData)]]
  def updateLastEvent(id: ConvId, time: Instant): Future[Option[(ConversationData, ConversationData)]]
  def updateConversationState(id: ConvId, state: ConversationState): Future[Option[(ConversationData, ConversationData)]]
  def updateAccessMode(id: ConvId, access: Set[Access], accessRole: Option[AccessRole], link: Option[ConversationData.Link] = None): Future[Option[(ConversationData, ConversationData)]]

  def createConversationWithMembers(convId:     ConvId,
                                    remoteId:   RConvId,
                                    convType:   ConversationType,
                                    creator:    UserId,
                                    members:    Set[UserId],
                                    name:       Option[String] = None,
                                    hidden:     Boolean = false,
                                    access:     Set[Access] = Set(Access.PRIVATE),
                                    accessRole: AccessRole = AccessRole.PRIVATE): Future[ConversationData]
}

class ConversationsContentUpdaterImpl(val storage:     ConversationStorage,
                                      teamId:          Option[TeamId],
                                      users:           UserService,
                                      membersStorage:  MembersStorage,
                                      messagesStorage: => MessagesStorage,
                                      tracking:        TrackingService) extends ConversationsContentUpdater {
  import com.waz.utils.events.EventContext.Implicits.global

  private implicit val dispatcher = new SerialDispatchQueue(name = "ConversationContentUpdater")

  val conversationsSignal: Signal[ConversationsSet] = storage.convsSignal

  val conversationsFuture = Future successful storage

  storage.convUpdated { case (prev, conv) =>
    if (prev.cleared != conv.cleared) {
      verbose(s"cleared updated will clear messages, prev: $prev, updated: $conv")
      messagesStorage.clear(conv.id, conv.cleared).recoverWithLog()
    }
  }

  override def convById(id: ConvId): Future[Option[ConversationData]] = storage.get(id)

  override def convByRemoteId(id: RConvId): Future[Option[ConversationData]] = storage.getByRemoteId(id)

  override def updateConversationName(id: ConvId, name: String) = storage.update(id, { conv =>
      if (conv.convType == ConversationType.Group)
        conv.copy(name = if (name.isEmpty) None else Some(name))
      else
        conv
    })

  override def setConvActive(id: ConvId, active: Boolean) = storage.update(id, { _.copy(isActive = active)})

  override def updateConversationArchived(id: ConvId, archived: Boolean) = storage.update(id, { c =>
    c.copy(archived = archived, archiveTime = c.lastEventTime)
  })

  override def updateConversationMuted(conv: ConvId, muted: Boolean) = storage.update(conv, { c =>
    c.copy(muted = muted, muteTime = c.lastEventTime)
  })

  override def updateConversationLastRead(id: ConvId, time: Instant) = storage.update(id, { conv =>
    verbose(s"updateConversationLastRead($id, $time)")
    conv.withLastRead(time)
  })

  override def updateConversationCleared(id: ConvId, time: Instant) = storage.update(id, { conv =>
    verbose(s"updateConversationCleared($id, $time)")
    //TODO: This is to distinguish initial values from initial sync from actual clearing. Ideally cleared time should be an Option[Instant]
    val adjustedTime = if (time == Instant.EPOCH) time.plusMillis(1) else time
    conv.withCleared(adjustedTime).withLastRead(adjustedTime)
  })

  override def updateConversationState(id: ConvId, state: ConversationState) = storage.update(id, { conv =>
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

  override def updateLastEvent(id: ConvId, time: Instant) = storage.update(id, { conv =>
    verbose(s"updateLastEvent($conv, $time)")

    if (conv.lastEventTime.isAfter(time)) conv
    else {
      debug(s"updating: $conv, lastEventTime: $time")
      conv.copy(lastEventTime = conv.lastEventTime max time)
    }
  })

  override def updateConversation(id: ConvId, convType: Option[ConversationType] = None, hidden: Option[Boolean] = None) =
    storage.update(id, { conv =>
      if (convType.forall(_ == conv.convType) && hidden.forall(_ == conv.hidden)) conv
      else conv.copy(convType = convType.getOrElse(conv.convType), hidden = hidden.getOrElse(conv.hidden))
    })

  override def setConversationHidden(id: ConvId, hidden: Boolean) = storage.update(id, _.copy(hidden = hidden))

  override def createConversationWithMembers(convId:     ConvId,
                                             remoteId:   RConvId,
                                             convType:   ConversationType,
                                             creator:    UserId,
                                             members:    Set[UserId],
                                             name:       Option[String] = None,
                                             hidden:     Boolean = false,
                                             access:     Set[Access] = Set(Access.PRIVATE),
                                             accessRole: AccessRole = AccessRole.PRIVATE) = {
    for {
      user <- users.getUsers(members.toSeq)
      conv <- storage.insert(
        ConversationData(
          convId,
          remoteId,
          name          = name,
          creator       = creator,
          convType      = convType,
          generatedName = NameUpdater.generatedName(convType)(user),
          hidden        = hidden,
          team          = teamId,
          isManaged     = teamId.map(_ => false),
          access        = access,
          accessRole    = Some(accessRole)))
      _ <- membersStorage.add(convId, members + creator)
    } yield conv
  }

  /**
   * Finds or creates local one-to-one conversation with given user and/or remoteId.
   * Updates remoteId if different and merges conversations if two are found for given local and remote id.
   *
   * Will not post created conversation to backend.
   * TODO: improve - it looks too complicated and duplicates some code
   */
  override def getOneToOneConversation(toUser: UserId, selfUserId: UserId, remoteId: Option[RConvId] = None, convType: ConversationType = ConversationType.OneToOne) =
    Serialized.future(('getOneToOneConversation, toUser)) {
      verbose(s"getOneToOneConversation($toUser, self: $selfUserId, remote: $remoteId, convType: $convType")
      val convId = ConvId(toUser.str)

      def createConversation() = createConversationWithMembers(convId, remoteId.getOrElse(RConvId(toUser.str)), convType, selfUserId, Set(toUser))

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

  override def hideIncomingConversation(user: UserId) = storage.update(ConvId(user.str), { conv =>
    if (conv.convType == ConversationType.Incoming) conv.copy(hidden = true) else conv
  })

  /**
   * Helper for event processing. Can be used whenever some event processing needs to access conversation by remoteId,
   * but conversation may not be present yet, for example if events are processed in wrong order.
   * Processing will be retried with delay.
   *
   * @param retryAsync - true if retry should be executed asynchronously, this should be used when executing from some ProcessingQueue, using delays in processing queue will block it
   */
  override def processConvWithRemoteId[A](remoteId: RConvId, retryAsync: Boolean, retryCount: Int = 0)(processor: ConversationData => Future[A])(implicit tag: LogTag, ec: ExecutionContext): Future[A] = {

    def retry() = CancellableFuture.delay(ConversationsService.RetryBackoff.delay(retryCount)).future .flatMap { _ =>
      processConvWithRemoteId(remoteId, retryAsync = false, retryCount + 1)(processor)(tag, ec)
    } (ec)

    convByRemoteId(remoteId) .flatMap {
      case Some(conv) => processor(conv)
      case None if retryCount > 3 =>
        val ex = new NoSuchElementException("No conversation data found") with NoStackTrace
        tracking.exception(ex, "No conversation data found")(tag)
        Future.failed(ex)
      case None =>
        warn(s"No conversation data found for remote id: $remoteId on try: $retryCount")(tag)
        if (retryAsync) {
          retry()
          Future.failed(BoxedError(new NoSuchElementException(s"No conversation data found for: $remoteId") with NoStackTrace)) // use BoxedError to avoid sending unnecessary hockey report
        } else
          retry()
    } (ec)
  }

  override def updateAccessMode(id: ConvId, access: Set[Access], accessRole: Option[AccessRole], link: Option[ConversationData.Link] = None) =
    storage.update(id, conv => conv.copy(access = access, accessRole = accessRole, link = if (!access.contains(Access.CODE)) None else link.orElse(conv.link)))
}
