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

import android.content.Context
import com.waz.ZLog._
import com.waz.api.{EphemeralExpiration, Message}
import com.waz.api.Message.Status
import com.waz.content._
import com.waz.model._
import com.waz.service.UserService
import com.waz.service.media.RichMediaContentParser
import com.waz.sync.SyncServiceHandle
import com.waz.threading.Threading
import com.waz.utils._
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future

class MessagesContentUpdater(context: Context, val messagesStorage: MessagesStorage, convs: ConversationStorage, users: UserService, sync: SyncServiceHandle, deletions: MsgDeletionStorage) {

  private implicit val tag: LogTag = logTagFor[MessagesContentUpdater]
  import Threading.Implicits.Background

  val contentParser = new RichMediaContentParser

  def getMessage(msgId: MessageId) = messagesStorage.getMessage(msgId)

  def deleteMessage(msg: MessageData) = messagesStorage.delete(msg)

  def deleteMessagesForConversation(convId: ConvId): Future[Unit] = messagesStorage.deleteAll(convId)

  def updateMessage(id: MessageId)(updater: MessageData => MessageData): Future[Option[MessageData]] = messagesStorage.update(id, updater) map {
    case Some((msg, updated)) if msg != updated =>
      assert(updated.id == id && updated.convId == msg.convId)
      Some(updated)
    case _ =>
      None
  }

  // removes messages and records deletion
  // this is used when user deletes a message manually (on local or remote device)
  def deleteOnUserRequest(ids: Seq[MessageId]) =
    deletions.insert(ids.map(id => MsgDeletion(id, Instant.now()))) flatMap { _ =>
      messagesStorage.remove(ids)
    }

  def addLocalMessage(msg: MessageData, state: Status = Status.PENDING) = Serialized.future("add local message", msg.convId) {

    def expiration =
      if (MessageData.EphemeralMessageTypes(msg.msgType))
        convs.get(msg.convId) map { _.fold(EphemeralExpiration.NONE)(_.ephemeral) }
      else Future successful EphemeralExpiration.NONE

    verbose(s"addLocalMessage: $msg")
    for {
      time <- nextLocalTime(msg.convId)
      _ = verbose(s"adding message to storage: $time")
      exp <- expiration
      res <- messagesStorage.addMessage(msg.copy(state = state, time = time, localTime = Instant.now, ephemeral = exp))
    } yield res
  }

  private def nextLocalTime(convId: ConvId) =
    messagesStorage.getLastMessage(convId) flatMap {
      case Some(msg) => Future successful msg.time
      case _ => convs.get(convId).map(_.fold(MessageData.UnknownInstant)(_.lastEventTime))
    } map { time =>
      Instant.now.max(time.plusMillis(1))
    }

  private def lastSentEventTime(convId: ConvId) =
    messagesStorage.getLastSentMessage(convId) flatMap {
      case Some(msg) => Future successful msg.time
      case _ => convs.get(convId).map(_.fold(MessageData.UnknownInstant)(_.lastEventTime))
    }

  /**
   * Updates last local message or creates new one.
   */
  def updateOrCreateLocalMessage(convId: ConvId, msgType: Message.Type, update: MessageData => MessageData, create: => MessageData) =
    Serialized.future("update-or-create-local-msg", convId, msgType) {
      messagesStorage.lastLocalMessage(convId, msgType) flatMap {
        case Some(msg) => // got local message, try updating
          @volatile var shouldCreate = false
          verbose(s"got local message: $msg, will update")
          updateMessage(msg.id) { msg =>
            if (msg.isLocal) update(msg)
            else { // msg was already synced, need to create new local message
              shouldCreate = true
              msg
            }
          } flatMap { res =>
            verbose(s"shouldCreate: $shouldCreate")
            if (shouldCreate) addLocalMessage(create).map(Some(_))
            else Future.successful(res)
          }
        case _ => addLocalMessage(create).map(Some(_))
      }
    }

  private[service] def addMessages(convId: ConvId, msgs: Seq[MessageData]): Future[Set[MessageData]] = {
    verbose(s"addMessages($msgs)")

    for {
      toAdd <- skipPreviouslyDeleted(msgs)
      (systemMsgs, contentMsgs) = toAdd.partition(_.isSystemMessage)
      sm <- addSystemMessages(convId, systemMsgs)
      cm <- addContentMessages(convId, contentMsgs)
    } yield sm.toSet ++ cm
  }

  private def skipPreviouslyDeleted(msgs: Seq[MessageData]) =
    deletions.getAll(msgs.map(_.id)) map { deletions =>
      val ds: Set[MessageId] = deletions.collect { case Some(MsgDeletion(id, _)) => id } (breakOut)
      msgs.filter(m => !ds(m.id))
    }

  private def addSystemMessages(convId: ConvId, msgs: Seq[MessageData]): Future[Seq[MessageData]] =
    if (msgs.isEmpty) Future.successful(Seq.empty)
    else {
      messagesStorage.getMessages(msgs.map(_.id): _*) flatMap { prev =>
        val prevIds: Set[MessageId] = prev.collect { case Some(m) => m.id } (breakOut)
        val toAdd = msgs.filterNot(m => prevIds.contains(m.id))

        RichFuture.traverseSequential(toAdd.groupBy(_.id).toSeq) { case (_, ms) =>
          val msg = ms.last
          messagesStorage.lastLocalMessage(convId, msg.msgType) flatMap {
            case Some(m) if m.userId == msg.userId =>
              verbose(s"lastLocalMessage(${msg.msgType}) : $m")

              if (m.msgType == Message.Type.MEMBER_JOIN || m.msgType == Message.Type.MEMBER_LEAVE) {
                val remaining = m.members.diff(msg.members)
                if (remaining.nonEmpty) addMessage(m.copy(id = MessageId(), members = remaining))
              }
              messagesStorage.delete(m.id).flatMap(_ => messagesStorage.addMessage(msg.copy(localTime = m.localTime)))
            case res =>
              verbose(s"lastLocalMessage(${msg.msgType}) returned: $res")
              messagesStorage.addMessage(msg)
          }
        }
      }
    }

  private def addContentMessages(convId: ConvId, msgs: Seq[MessageData]): Future[Set[MessageData]] = {
    // merge data from multiple events in single message
    def merge(msgs: Seq[MessageData]): MessageData = {

      def mergeLocal(m: MessageData, msg: MessageData) =
        msg.copy(id = m.id, localTime = m.localTime)

      def mergeMatching(prev: MessageData, msg: MessageData) = {
        val u = prev.copy(msgType = msg.msgType, time = if (msg.time.isBefore(prev.time) || prev.isLocal) msg.time else prev.time, protos = prev.protos ++ msg.protos, content = msg.content)
        prev.msgType match {
          case Message.Type.RECALLED => prev // ignore updates to already recalled message
          case _ => u
        }
      }

      if (msgs.size == 1) msgs.head
      else msgs.reduce { (prev, msg) =>
        if (prev.isLocal && prev.userId == msg.userId) mergeLocal(prev, msg)
        else if (prev.userId == msg.userId) mergeMatching(prev, msg)
        else {
          warn(s"got message id conflict, will add it with random id, existing: $prev, new: $msg")
          addMessage(msg.copy(id = MessageId()))
          prev
        }
      }
    }

    if (msgs.isEmpty) Future.successful(Set.empty)
    else
      messagesStorage.updateOrCreateAll (
        msgs.groupBy(_.id).mapValues { data =>
          { (prev: Option[MessageData]) => merge(prev.toSeq ++ data) }
        }
      )
  }

  private[service] def addMessage(msg: MessageData): Future[Option[MessageData]] = addMessages(msg.convId, Seq(msg)).map(_.headOption)

  // updates server timestamp for local messages, this should make sure that local messages are ordered correctly after one of them is sent
  def updateLocalMessageTimes(conv: ConvId, prevTime: Instant, time: Instant) =
    messagesStorage.findLocalFrom(conv, prevTime) flatMap { local =>
      verbose(s"local messages from $prevTime: $local")
      messagesStorage updateAll2(local.map(_.id), { m =>
        verbose(s"try updating local message time, msg: $m, time: $time")
        if (m.isLocal) m.copy(time = time.plusMillis(m.time.toEpochMilli - prevTime.toEpochMilli)) else m
      })
    }
}
