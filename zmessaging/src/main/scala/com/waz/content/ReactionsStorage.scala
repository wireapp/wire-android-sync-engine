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

import android.content.Context
import com.waz.ZLog._
import com.waz.model.Liking.{Action, LikingDao}
import com.waz.model._
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.{AggregatingSignal, EventContext}
import com.waz.utils._
import org.threeten.bp.Instant
import org.threeten.bp.Instant.EPOCH

import scala.collection.{breakOut, mutable}
import scala.concurrent.duration._
import scala.concurrent.Future

class ReactionsStorage(context: Context, storage: Database) extends CachedStorageImpl[(MessageId, UserId), Liking](new TrimmingLruCache(context, Fixed(MessagesStorage.cacheSize)), storage)(LikingDao, "LikingStorage") {
  import ReactionsStorage._
  import EventContext.Implicits.global

  private implicit val dispatcher = new SerialDispatchQueue()

  private val likesCache = new TrimmingLruCache[MessageId, Map[UserId, Instant]](context, Fixed(1024))
  private val maxTime = returning(new AggregatingSignal[Instant, Instant](onChanged.map(_.maxBy(_.timestamp).timestamp), storage.read(LikingDao.findMaxTime(_)), _ max _))(_.disableAutowiring())

  onChanged.on(dispatcher) { likes =>
    likes.groupBy(_.message) foreach { case (msg, ls) =>
      Option(likesCache.get(msg)) foreach { current =>
        val (toAdd, toRemove) = ls.partition(_.action == Action.Like)
        likesCache.put(msg, current -- toRemove.map(_.user) ++ toAdd.map(l => l.user -> l.timestamp))
      }
    }
  }

  private def updateCache(msg: MessageId, likings: Iterable[Liking]) = {
    val users: Map[UserId, Instant] = likings.collect { case l if l.action == Action.Like => l.user -> l.timestamp } (breakOut)
    likesCache.put(msg, users)
    Likes(msg, users)
  }

  override def insert(vs: Traversable[Liking]): Future[Set[Liking]] = {
    val values = new mutable.HashMap[(MessageId, UserId), Liking]
    vs foreach { v =>
      if (values.get(v.id).forall(_.timestamp.isBefore(v.timestamp)))
        values(v.id) = v
    }
    updateOrCreateAll2(values.keys, { (id, v) => v.fold(values(id)) { _ max values(id) }})
  }

  def getLikes(msg: MessageId): Future[Likes] = Future {
    Option(likesCache.get(msg))
  } flatMap {
    case Some(users) => Future.successful(Likes(msg, users))
    case None => find(_.message == msg, LikingDao.findForMessage(msg)(_), identity) map { updateCache(msg, _) }
  }

  def addOrUpdate(liking: Liking): Future[Likes] = {
    if (liking.timestamp == EPOCH) updateOrCreate(liking.id, l => l.copy(timestamp = maxTime.currentValue.getOrElse(l.timestamp) + 1.milli, action = liking.action), liking) // local update
    else updateOrCreate(liking.id, _ max liking, liking)
  }.flatMap(_ => getLikes(liking.message))

  def loadAll(msgs: Seq[MessageId]): Future[Vector[Likes]] = Future {
    msgs.map(m => m -> Option(likesCache.get(m))).toMap
  } flatMap { cached =>
    val toLoad: Set[MessageId] = cached.collect { case (id, None) => id } (breakOut)
    find(l => toLoad(l.message), LikingDao.findForMessages(toLoad)(_), identity) map { likings =>
      val usersMap = cached.mapValues(_.getOrElse(Map.empty)) ++ likings.groupBy(_.message).map { case (msg, ls) => msg -> likers(ls) }
      msgs.map { msg =>
        val users = usersMap(msg)
        if (likesCache.get(msg) == null) likesCache.put(msg, users)
        Likes(msg, users)
      } (breakOut)
    }
  }
}

object ReactionsStorage {
  private implicit val logTag: LogTag = logTagFor[ReactionsStorage]

  private def likers(likings: Seq[Liking]): Map[UserId, Instant] =
    likings.collect { case l if l.action == Action.Like => l.user -> l.timestamp } (breakOut)
}

case class Likes(message: MessageId, likers: Map[UserId, Instant])
