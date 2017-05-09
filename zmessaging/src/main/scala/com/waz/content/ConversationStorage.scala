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

import com.waz.ZLog._
import com.waz.api.Verification
import com.waz.api.Verification.UNKNOWN
import com.waz.model.ConversationData.ConversationDataDao
import com.waz.model.ConversationData.ConversationType.Group
import com.waz.model._
import com.waz.service.SearchKey
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events._

import scala.collection._
import scala.collection.immutable.SortedSet
import scala.concurrent.Future

class ConversationStorage(storage: ZmsDatabase) extends CachedStorageImpl[ConvId, ConversationData](new UnlimitedLruCache(), storage)(ConversationDataDao, "ConversationStorage_Cached") {
  import ConversationStorage._
  import EventContext.Implicits.global
  private implicit val dispatcher = new SerialDispatchQueue(name = "ConversationStorage")

  private val remoteMap = new mutable.HashMap[RConvId, ConvId]()
  private val conversationsById = new mutable.HashMap[ConvId, ConversationData]()
  def conversations = conversationsById.values.toIndexedSeq

  val convsSignal = Signal[ConversationsSet]()

  val convAdded = EventStream[ConversationData]()
  val convDeleted = EventStream[ConversationData]()
  val convUpdated = EventStream[(ConversationData, ConversationData)]() // (prev, updated)

  onAdded.on(dispatcher) { cs =>
    verbose(s"convs added: $cs")
    cs foreach { c =>
      conversationsById.put(c.id, c)
      remoteMap.put(c.remoteId, c.id)
    }

    convsSignal mutate { _ ++ cs }

    cs foreach (convAdded ! _)

    updateSearchKey(cs)
  }

  def setUnknownVerification(convId: ConvId) = update(convId, { c => c.copy(verified = if (c.verified == Verification.UNVERIFIED) UNKNOWN else c.verified) })

  onDeleted.on(dispatcher) { cs =>
    cs foreach { c =>
      conversationsById.remove(c) foreach { convDeleted ! _ }
    }

    convsSignal mutate { _ -- cs.toSet }
  }

  onUpdated.on(dispatcher) { cs =>
    verbose(s"convs updated: $cs")
    cs foreach { case (prev, conv) =>
      conversationsById.put(conv.id, conv)
      if (prev.remoteId != conv.remoteId) {
        remoteMap.remove(prev.remoteId)
        remoteMap.put(conv.remoteId, conv.id)
      }
    }

    convsSignal mutate { _ -- cs.map(_._1.id).toSet ++ cs.map(_._2) }

    cs foreach (convUpdated ! _)

    updateSearchKey(cs collect { case (p, c) if p.name != c.name || (p.convType == Group) != (c.convType == Group) || (c.name.nonEmpty && c.searchKey.isEmpty) => c })
  }

  private val init = for {
    convs <- find[ConversationData, Vector[ConversationData]](_ => true, db => ConversationDataDao.iterating(ConversationDataDao.listCursor(db)), identity)
    updater = (c: ConversationData) => c.copy(hasVoice = false, unjoinedCall = false, searchKey = c.savedOrFreshSearchKey) // on initial app start, no calls are assumed to be ongoing or even unjoined
    convUpdates <- updateAll2(convs.map(_.id), updater)
  } yield {
    val updated = convs map updater
    updated foreach { c =>
      conversationsById(c.id) = c
      remoteMap(c.remoteId) = c.id
    }

    convsSignal ! ConversationsSet(updated.to[SortedSet])

    conversationsById
  }

  private def updateSearchKey(cs: Seq[ConversationData]) =
    if (cs.isEmpty) Future successful Nil
    else updateAll2(cs.map(_.id), _.withFreshSearchKey)

  def apply[A](f: (GenMap[ConvId, ConversationData], GenMap[RConvId, ConvId]) => A): Future[A] = init map { convById => f(convById, remoteMap) }

  def getByRemoteId(remoteId: RConvId): Future[Option[ConversationData]] = init map { convById =>
    remoteMap.get(remoteId).flatMap(convById.get)
  }

  def getByRemoteIds(remoteId: Traversable[RConvId]): Future[Seq[ConvId]] = init map { convById =>
    remoteId.flatMap(remoteMap.get).toVector
  }

  def getAll = init map { _ => conversations }

  override def list: Future[Vector[ConversationData]] = init map { _.values.toVector }

  def updateLocalId(oldId: ConvId, newId: ConvId) =
    for {
      _    <- remove(newId)
      conv <- get(oldId)
      res  <- conv.fold(Future.successful(Option.empty[ConversationData])) { c =>
        remove(oldId) flatMap { _ => insert(c.copy(id = newId)).map { Some(_) } }
      }
    } yield res

  def search(prefix: SearchKey, self: UserId, handleOnly: Boolean): Future[Vector[ConversationData]] = storage(ConversationDataDao.search(prefix, self, handleOnly)(_))
}

object ConversationStorage {
  private implicit val logTag: LogTag = logTagFor[ConversationStorage]
}

// this wrapper provides a check for content equality instead of the equality based on total ordering provided by the sorted set
case class ConversationsSet(conversations: SortedSet[ConversationData]) {
  def ++(cs: GenTraversableOnce[ConversationData]) = ConversationsSet(conversations ++ cs)
  def --(cs: GenSet[ConvId]) = ConversationsSet(conversations.filter(c => !cs(c.id)))

  override def equals(any: Any) = any match {
    case other @ ConversationsSet(cs) => this.eq(other) || cs.iterator.corresponds(conversations)(_ == _)
    case _ => false
  }
}
