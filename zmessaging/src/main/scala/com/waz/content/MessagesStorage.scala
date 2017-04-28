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
package com.waz.content

import java.util.concurrent.ConcurrentHashMap

import android.content.Context
import com.waz.ZLog._
import com.waz.api.{ErrorResponse, Message, MessageFilter}
import com.waz.model.MessageData.{MessageDataDao, MessageEntry}
import com.waz.model._
import com.waz.service.Timeouts
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils._
import com.waz.utils.events.{EventStream, Signal, SourceSignal}
import org.threeten.bp.Instant

import scala.collection._
import scala.concurrent.Future
import scala.util.Failure

class MessagesStorage(context: Context, storage: ZmsDatabase, userId: UserId, convs: ConversationStorage, users: UsersStorage, msgAndLikes: => MessageAndLikesStorage, timeouts: Timeouts) extends
    CachedStorageImpl[MessageId, MessageData](new TrimmingLruCache[MessageId, Option[MessageData]](context, Fixed(MessagesStorage.cacheSize)), storage)(MessageDataDao, "MessagesStorage_Cached") {

  import com.waz.utils.events.EventContext.Implicits.global

  private implicit val tag: LogTag = logTagFor[MessagesStorage]
  private implicit val dispatcher = new SerialDispatchQueue(name = "MessagesStorage")

  val messageAdded = onAdded
  val messageUpdated = onUpdated

  val messageChanged = EventStream.union(messageAdded, messageUpdated.map(_.map(_._2)))

  val onMessageSent = EventStream[MessageData]()
  val onMessageFailed = EventStream[(MessageData, ErrorResponse)]()

  private val indexes = new ConcurrentHashMap[ConvId, ConvMessagesIndex]
  private val filteredIndexes = new MultiKeyLruCache[ConvId, MessageFilter, ConvMessagesIndex](MessagesStorage.filteredMessagesCacheSize)

  lazy val incomingMessages = storage {
    MessageDataDao.listIncomingMessages(userId, System.currentTimeMillis - timeouts.messages.incomingTimeout.toMillis)(_)
  } map { new IncomingMessages(userId, _, timeouts) }

  def msgsIndex(conv: ConvId): Future[ConvMessagesIndex] =
    Option(indexes.get(conv)).fold {
      Future(returning(new ConvMessagesIndex(conv, this, userId, users, convs, msgAndLikes, storage))(indexes.put(conv, _)))
    } {
      Future.successful
    }

  def msgsFilteredIndex(conv: ConvId, messageFilter: MessageFilter): Future[ConvMessagesIndex] =
    filteredIndexes.get(conv, messageFilter).fold {
      Future(returning(new ConvMessagesIndex(conv, this, userId, users, convs, msgAndLikes, storage, filter = Some(messageFilter)))(filteredIndexes.put(conv, messageFilter, _)))
    } {
      Future.successful
    }

  def msgsFilteredIndex(conv: ConvId): Seq[ConvMessagesIndex] = filteredIndexes.get(conv).values.toSeq

  onAdded { added =>
    Future.traverse(added.groupBy(_.convId)) { case (convId, msgs) =>{
        msgsFilteredIndex(convId).foreach(_.add(msgs))
        msgsIndex(convId).flatMap { index =>
          index.add(msgs).flatMap(_ => index.firstMessageId) map { first =>
            // XXX: calling update here is a bit ugly
            val ms = msgs.map {
              case msg if first.contains(msg.id) =>
                update(msg.id, _.copy(firstMessage = first.contains(msg.id)))
                msg.copy(firstMessage = first.contains(msg.id))
              case msg => msg
            }

            updateIncomingMessagesList(convId, ms)
          }
        }
      }
    } .recoverWithLog(reportHockey = true)
  }

  onUpdated { updates =>
    Future.traverse(updates.groupBy(_._1.convId)) { case (convId, msgs) =>{
        msgsFilteredIndex(convId).foreach(_.update(msgs))
        for {
          index <- msgsIndex(convId)
          _ <- index.update(msgs)
          _ <- updateIncomingMessagesList(convId, msgs.map(_._2))
        } yield ()
      } .recoverWithLog(reportHockey = true)
    }
  }

  onDeleted { ids =>
    incomingMessages.map { _.remove(ids.toSet) }
  }

  convs.convUpdated.on(dispatcher) {
    case (prev, updated) if updated.lastRead != prev.lastRead =>
      verbose(s"lastRead of conversation ${updated.id} updated to ${updated.lastRead}, will update unread count")
      msgsIndex(updated.id).map(_.updateLastRead(updated)).recoverWithLog()
    case _ => // ignore
  }

  def addMessage(msg: MessageData): Future[MessageData] = put(msg.id, msg)

  def countUnread(conv: ConvId, lastReadTime: Instant): Future[Int] =
    storage { MessageDataDao.countNewer(conv, lastReadTime)(_) } .future.flatMap { count =>
      var cachedCount = 0
      foreachCached { m =>
        if (! m.isLocal && m.convId == conv && m.time.isAfter(lastReadTime)) cachedCount += 1
      } map { _ =>
        verbose(s"countUnread($conv, $lastReadTime), count: $count, cached: $cachedCount")
        math.max(count.toInt, cachedCount)
      }
    }

  def countSentByType(selfUserId: UserId, tpe: Message.Type): Future[Int] = storage(MessageDataDao.countSentByType(selfUserId, tpe)(_).toInt)

  def countMessages(conv: ConvId, p: MessageEntry => Boolean): Future[Int] = storage(MessageDataDao.countMessages(conv, p)(_))

  def countLaterThan(conv: ConvId, time: Instant): Future[Long] = storage(MessageDataDao.countLaterThan(conv, time)(_))

  def getMessage(id: MessageId): Future[Option[MessageData]] = get(id)

  def getMessages(ids: MessageId*): Future[Seq[Option[MessageData]]] = getAll(ids)

  def getIncomingMessages: Signal[List[MessageData]] = Signal.future(incomingMessages).flatMap(_.messages)

  def getEntries(conv: ConvId) = Signal.future(msgsIndex(conv)).flatMap(_.signals.messagesCursor)

  def lastMessage(conv: ConvId) = Signal.future(msgsIndex(conv)).flatMap(_.signals.lastMessage)

  def lastMessageFromSelfAndFromOther(conv: ConvId) = Signal.future(msgsIndex(conv)).flatMap(mi => mi.signals.lastMessageFromSelf zip mi.signals.lastMessageFromOther)

  def getLastMessage(conv: ConvId) = msgsIndex(conv).flatMap(_.getLastMessage)

  def getLastSentMessage(conv: ConvId) = msgsIndex(conv).flatMap(_.getLastSentMessage)

  def unreadCount(conv: ConvId): Signal[Int] = Signal.future(msgsIndex(conv)).flatMap(_.signals.unreadCount)

  def lastRead(conv: ConvId) = Signal.future(msgsIndex(conv)).flatMap(_.signals.lastReadTime)

  def lastLocalMessage(conv: ConvId, tpe: Message.Type): Future[Option[MessageData]] =
    msgsIndex(conv).flatMap(_.lastLocalMessage(tpe)).flatMap {
      case Some(id) => get(id)
      case _ => CancellableFuture.successful(None)
    }

  def findLocalFrom(conv: ConvId, time: Instant) =
    find(m => m.convId == conv && m.isLocal && !m.time.isBefore(time), MessageDataDao.findLocalFrom(conv, time)(_), identity)

  def findMessagesFrom(conv: ConvId, time: Instant) =
    find(m => m.convId == conv && !m.time.isBefore(time), MessageDataDao.findMessagesFrom(conv, time)(_), identity)

  def delete(msg: MessageData): Future[Unit] =
    for {
      _ <- super.remove(msg.id)
      _ <- Future(msgsFilteredIndex(msg.convId).foreach(_.delete(msg)))
      index <- msgsIndex(msg.convId)
      _ <- index.delete(msg)
    } yield ()

  def delete(id: MessageId): Future[Unit] = remove(id)

  override def remove(id: MessageId): Future[Unit] =
    getMessage(id) flatMap {
      case Some(msg) => delete(msg)
      case None =>
        warn(s"No message found for: $id")
        Future.successful(())
    }

  override def remove(keys: Iterable[MessageId]): Future[Unit] =
    for {
      fromDb <- getAll(keys)
      msgs = fromDb.collect { case Some(m) => m }
      _ <- super.remove(keys)
      _ <- Future.traverse(msgs) { msg => {
        Future(msgsFilteredIndex(msg.convId).foreach(_.delete(msg))).zip(
        msgsIndex(msg.convId).flatMap(_.delete(msg)))
      }
          } map { _ => () }
    } yield ()

  def delete(conv: ConvId, upTo: Instant): Future[Unit] = {
    verbose(s"delete($conv, $upTo)")
    for {
      _ <- storage { MessageDataDao.deleteUpTo(conv, upTo)(_) } .future
      _ <- deleteCached(m => m.convId == conv && ! m.time.isAfter(upTo))
      _ <- Future(msgsFilteredIndex(conv).foreach(_.delete(upTo)))
      _ <- msgsIndex(conv).flatMap(_.delete(upTo))
      _ <- incomingMessages.map(_.remove(conv))
    } yield ()
  }

  def clear(convId: ConvId, clearTime: Instant): Future[Unit] = {
    verbose(s"clear($convId, $clearTime)")
    delete(convId, clearTime)
  }

  def deleteAll(conv: ConvId) = {
    verbose(s"deleteAll($conv)")
    for {
      _ <- storage { MessageDataDao.deleteForConv(conv)(_) } .future
      _ <- deleteCached(_.convId == conv)
      _ <- Future(msgsFilteredIndex(conv).foreach(_.delete()))
      _ <- msgsIndex(conv).flatMap(_.delete())
      _ <- incomingMessages.map(_.remove(conv))
    } yield ()
  }

  private def updateIncomingMessagesList(convId: ConvId, msgs: Seq[MessageData]) =
    convs.get(convId) flatMap { conv =>
      if (conv.exists(_.muted)) Future.successful(())
      else incomingMessages map { _.add(msgs) }
    }
}

object MessagesStorage {
  val cacheSize = 12048
  val filteredMessagesCacheSize = 32
  val FirstMessageTypes = {
    import Message.Type._
    Set(TEXT, TEXT_EMOJI_ONLY, KNOCK, ASSET, ANY_ASSET, VIDEO_ASSET, AUDIO_ASSET, LOCATION)
  }
}

class IncomingMessages(selfUserId: UserId, initial: Seq[MessageData], timeouts: Timeouts) {
  import scala.collection.breakOut
  private implicit val tag: LogTag = logTagFor[IncomingMessages]

  private val messagesSource = new SourceSignal[SortedMap[(Long, MessageId), MessageData]](Some(initial.map(m => (m.localTime.toEpochMilli, m.id) -> m)(breakOut)))

  def sinceTime = System.currentTimeMillis - timeouts.messages.incomingTimeout.toMillis

  val messages = messagesSource map { msgs =>
    val time = sinceTime
    msgs.dropWhile(_._1._1 < time).values.toList
  }

  def add(msgs: Seq[MessageData]) = {
    val time = sinceTime
    val ms = msgs filter(m => m.userId != selfUserId && m.localTime.toEpochMilli > time) map { msg => (msg.localTime.toEpochMilli, msg.id) -> msg }
    val ids = msgs.map(_.id).toSet
    messagesSource.mutate { _.dropWhile(_._1._1 < time).filter(e => !ids.contains(e._1._2)) ++ ms }
  }

  def remove(ids: Set[MessageId]) =
    messagesSource.mutate(_.from((sinceTime, MessageId.Empty)).filter(e => !ids.contains(e._1._2)))

  def remove(msg: MessageData) = {
    messagesSource.mutate(_ - (msg.localTime.toEpochMilli -> msg.id))
  }

  def remove(conv: ConvId) = {
    messagesSource.mutate(_.filter { case (k, v) => v.convId != conv})
  }

  def remove(conv: ConvId, upTo: Instant) = {
    messagesSource.mutate(_.filter { case (k, v) => v.convId != conv || v.time.isAfter(upTo)})
  }
}

class MessageAndLikesStorage(selfUserId: UserId, messages: MessagesStorage, likings: ReactionsStorage) {
  import com.waz.threading.Threading.Implicits.Background
  import com.waz.utils.events.EventContext.Implicits.global

  private implicit val tag: LogTag = logTagFor[MessageAndLikesStorage]

  val onUpdate = EventStream[MessageId]() // TODO: use batching, maybe report new message data instead of just id

  messages.onDeleted { ids => ids foreach { onUpdate ! _ } }
  messages.messageChanged { ms => ms foreach { m => onUpdate ! m.id }}
  likings.onChanged { _ foreach { l => onUpdate ! l.message } }

  def apply(ids: Seq[MessageId]): Future[Seq[MessageAndLikes]] =
    messages.getMessages(ids: _*) flatMap { msgs => withLikes(msgs.flatten) }

  def getMessageAndLikes(id: MessageId): Future[Option[MessageAndLikes]] =
    messages.getMessage(id).flatMap(_.fold2(Future.successful(None), msg => combineWithLikes(msg).map(Some(_))))

  def combineWithLikes(msg: MessageData): Future[MessageAndLikes] =
    likings.getLikes(msg.id).map(l => combine(msg, l, selfUserId))

  def withLikes(msgs: Seq[MessageData]): Future[Seq[MessageAndLikes]] = {
    val ids: Set[MessageId] = msgs.map(_.id)(breakOut)
      likings.loadAll(msgs.map(_.id)).map { likes =>
      val likesById = likes.by[MessageId, Map](_.message)
      returning(msgs.map(msg => combine(msg, likesById(msg.id), selfUserId))) { _ =>
        verbose(s"combined ${ids.size} message(s) with ${likesById.size} liking(s)")
      }
    }.andThen { case Failure(t) => error("failed while adding likings to messages", t) }
  }

  def combine(msg: MessageData, likes: Likes, selfUserId: UserId): MessageAndLikes =
    if (likes.likers.isEmpty) MessageAndLikes(msg, Vector(), false)
    else sortedLikes(likes, selfUserId) match { case (likers, selfLikes) => MessageAndLikes(msg, likers, selfLikes) }


  def sortedLikes(likes: Likes, selfUserId: UserId): (IndexedSeq[UserId], Boolean) =
    (likes.likers.toVector.sortBy(_._2).map(_._1), likes.likers contains selfUserId)
}
