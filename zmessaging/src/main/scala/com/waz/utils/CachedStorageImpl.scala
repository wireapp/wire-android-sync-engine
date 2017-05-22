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
package com.waz.utils

import android.support.v4.util.LruCache
import com.waz.ZLog._
import com.waz.content.Database
import com.waz.db.DaoIdOps
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{AggregatingSignal, EventStream, Signal}
import com.waz.utils.wrappers.DB

import scala.collection.JavaConverters._
import scala.collection.{GenTraversableOnce, breakOut}
import scala.collection.generic._
import scala.concurrent.Future

trait StorageDao[K, V] {
  val idExtractor: (V => K)
  def getById(key: K)(implicit db: DB): Option[V]
  def getAll(keys: Set[K])(implicit db: DB): Seq[V]
  def list(implicit db: DB): Seq[V]
  def insertOrReplace(items: GenTraversableOnce[V])(implicit db: DB): Unit
  def deleteEvery(ids: GenTraversableOnce[K])(implicit db: DB): Unit
}

object StorageDao {

  implicit class DbDao[K, V](dao: DaoIdOps[V] { type IdVals = K }) extends StorageDao[K, V] {
    override val idExtractor: (V) => K = dao.idExtractor
    override def getById(key: K)(implicit db: DB): Option[V] = dao.getById(key)
    override def getAll(keys: Set[K])(implicit db: DB): Seq[V] = dao.getAll(keys)
    override def list(implicit db: DB) = dao.list
    override def deleteEvery(ids: GenTraversableOnce[K])(implicit db: DB): Unit = dao.deleteEvery(ids)
    override def insertOrReplace(items: GenTraversableOnce[V])(implicit db: DB): Unit = dao.insertOrReplace(items)
  }
}

trait CachedStorage[K, V] {

  val onAdded: EventStream[Seq[V]]
  val onUpdated: EventStream[Seq[(V, V)]]
  val onDeleted: EventStream[Seq[K]]

  protected def load(key: K)(implicit db: DB): Option[V]
  protected def load(keys: Set[K])(implicit db: DB): Seq[V]
  protected def save(values: Seq[V])(implicit db: DB): Unit
  protected def delete(keys: Iterable[K])(implicit db: DB): Unit
  protected def updateInternal(key: K, updater: V => V)(current: V): Future[Option[(V, V)]]

  def find[A, B](predicate: V => Boolean, search: DB => Managed[TraversableOnce[V]], mapping: V => A)(implicit cb: CanBuild[A, B]): Future[B]
  def filterCached(f: V => Boolean): Future[Vector[V]]
  def foreachCached(f: V => Unit): Future[Unit]
  def deleteCached(predicate: V => Boolean): Future[Unit]

  def onChanged(key: K): EventStream[V]
  def onRemoved(key: K): EventStream[K]

  def optSignal(key: K): Signal[Option[V]]
  def signal(key: K): Signal[V]

  def insert(v: V): Future[V]
  def insert(vs: Traversable[V]): Future[Set[V]]

  def get(key: K): Future[Option[V]]
  def getOrCreate(key: K, creator: => V): Future[V]
  def list(): Future[Seq[V]]
  def getAll(keys: Traversable[K]): Future[Seq[Option[V]]]

  def update(key: K, updater: V => V): Future[Option[(V, V)]]
  def updateAll(updaters: scala.collection.Map[K, V => V]): Future[Seq[(V, V)]]
  def updateAll2(keys: Iterable[K], updater: V => V): Future[Seq[(V, V)]]

  def updateOrCreate(key: K, updater: V => V, creator: => V): Future[V]

  def updateOrCreateAll(updaters: K Map (Option[V] => V)): Future[Set[V]]
  def updateOrCreateAll2(keys: Iterable[K], updater: ((K, Option[V]) => V)): Future[Set[V]]


  def put(key: K, value: V): Future[V]
  def getRawCached(key: K): Option[V]

  def remove(key: K): Future[Unit]
  def remove(keys: Iterable[K]): Future[Unit]

  def cacheIfNotPresent(key: K, value: V): Unit
}

class CachedStorageImpl[K, V](cache: LruCache[K, Option[V]], db: Database)(implicit val dao: StorageDao[K, V], tag: LogTag = "CachedStorage") extends CachedStorage[K, V] {
  private implicit val dispatcher = new SerialDispatchQueue(name = tag + "_Dispatcher")

  val onAdded = EventStream[Seq[V]]()
  val onUpdated = EventStream[Seq[(V, V)]]()
  val onDeleted = EventStream[Seq[K]]()

  val onChanged = onAdded.union(onUpdated.map(_.map(_._2)))

  protected def load(key: K)(implicit db: DB): Option[V] = dao.getById(key)

  protected def load(keys: Set[K])(implicit db: DB): Seq[V] = dao.getAll(keys)

  protected def save(values: Seq[V])(implicit db: DB): Unit = dao.insertOrReplace(values)

  protected def delete(keys: Iterable[K])(implicit db: DB): Unit = dao.deleteEvery(keys)

  private def cachedOrElse(key: K, default: => Future[Option[V]]): Future[Option[V]] =
    Option(cache.get(key)).fold(default)(Future.successful)

  private def loadFromDb(key: K) = db.read { load(key)(_) } map { value =>
    Option(cache.get(key)).getOrElse {
      cache.put(key, value)
      value
    }
  }

  def find[A, B](predicate: V => Boolean, search: DB => Managed[TraversableOnce[V]], mapping: V => A)(implicit cb: CanBuild[A, B]): Future[B] = Future {
    val matches = cb.apply()
    val snapshot = cache.snapshot.asScala

    snapshot.foreach {
      case (k, Some(v)) if predicate(v) => matches += mapping(v)
      case _ =>
    }
    (snapshot.keySet, matches)
  } flatMap { case (wasCached, matches) =>
    db.read { database =>
      val uncached = Map.newBuilder[K, V]
      search(database).acquire { rows =>
        rows.foreach { v =>
          val k = dao.idExtractor(v)
          if (! wasCached(k)) {
            matches += mapping(v)
            uncached += k -> v
          }
        }

        (matches.result, uncached.result)
      }
    }
  } map { case (results, uncached) =>
    // cache might have changed already at this point, but that would mean the write would have been issued after this read anyway, so we can safely return the outdated values here

    uncached.foreach { case (k, v) =>
      if (cache.get(k) eq null) cache.put(k, Some(v))
    }

    results
  }

  def filterCached(f: V => Boolean) = Future { cache.snapshot.values().asScala.filter(_.exists(f)).map(_.get).toVector }

  def foreachCached(f: V => Unit) = Future {
    cache.snapshot.asScala.foreach {
      case (k, Some(v)) => f(v)
      case _ =>
    }
  }

  def deleteCached(predicate: V => Boolean) = Future {
    cache.snapshot.asScala.collect { case (k, Some(v)) if predicate(v) => k } foreach { cache.remove }
  }

  def onChanged(key: K): EventStream[V] = onChanged.map(_.view.filter(v => dao.idExtractor(v) == key).lastOption).collect { case Some(v) => v }

  def onRemoved(key: K): EventStream[K] = onDeleted.map(_.view.filter(_ == key).lastOption).collect { case Some(k) => k }

  def optSignal(key: K): Signal[Option[V]] = {
    val changeOrDelete = onChanged(key).map(Option(_)).union(onRemoved(key).map(_ => Option.empty[V]))
    new AggregatingSignal[Option[V], Option[V]](changeOrDelete, get(key), { (_, v) => v })
  }

  def signal(key: K): Signal[V] = optSignal(key).collect { case Some(v) => v }

  def insert(v: V) = put(dao.idExtractor(v), v)

  def insert(vs: Traversable[V]) =
    updateOrCreateAll(vs.map { v => dao.idExtractor(v) -> { (_: Option[V]) => v } }(breakOut))

  def get(key: K): Future[Option[V]] = cachedOrElse(key, Future { cachedOrElse(key, loadFromDb(key)) }.flatMap(identity))

  def getOrCreate(key: K, creator: => V): Future[V] = get(key) flatMap { value =>
    value.orElse(Option(cache.get(key)).flatten).fold(addInternal(key, creator))(Future.successful)
  }

  def list() = db.read { dao.list(_) } // TODO: should we update cache?

  def getAll(keys: Traversable[K]): Future[Seq[Option[V]]] = {
    val cachedEntries = keys.flatMap { key => Option(cache.get(key)) map { value => (key, value) } }.toMap
    val missingKeys = keys.toSet -- cachedEntries.keys

    db.read { db => load(missingKeys)(db) } map { loadedEntries =>
      val loadedMap: Map[K, Option[V]] = loadedEntries.map { value =>
        val key = dao.idExtractor(value)
        Option(cache.get(key)).map(m => (key, m)).getOrElse {
          cache.put(key, Some(value))
          (key, Some(value))
        }
      }(breakOut)

      keys .map { key =>
        returning(Option(cache.get(key)).orElse(loadedMap.get(key).orElse(cachedEntries.get(key))).getOrElse(None)) { cache.put(key, _) }
      } (breakOut) : Vector[Option[V]]
    }
  }

  def update(key: K, updater: V => V): Future[Option[(V, V)]] = get(key) flatMap { loaded =>
    val prev = Option(cache.get(key)).getOrElse(loaded)
    prev.fold(Future successful Option.empty[(V, V)]) { updateInternal(key, updater)(_) }
  }

  def updateAll(updaters: scala.collection.Map[K, V => V]): Future[Seq[(V, V)]] =
    updateAll2(updaters.keys.toVector, { v => updaters(dao.idExtractor(v))(v) })

  def updateAll2(keys: Iterable[K], updater: V => V): Future[Seq[(V, V)]] =
    if (keys.isEmpty) Future successful Seq.empty[(V, V)]
    else
      getAll(keys) flatMap { values =>
        val updated = keys.iterator.zip(values.iterator) .flatMap { case (k, v) =>
          Option(cache.get(k)).flatten.orElse(v).flatMap { value =>
            val updated = updater(value)
            if (updated != value) {
              cache.put(k, Some(updated))
              Some(value -> updated)
            } else None
          }
        } .toVector

        if (updated.isEmpty) Future.successful(Vector.empty)
        else
          returning (db { save(updated.map(_._2))(_) } .future.map { _ => updated }) { _ =>
            onUpdated ! updated
          }
      }

  def updateOrCreate(key: K, updater: V => V, creator: => V): Future[V] = get(key) flatMap { loaded =>
    val prev = Option(cache.get(key)).getOrElse(loaded)
    prev.fold { addInternal(key, creator) } { v => updateInternal(key, updater)(v).map(_.fold(v)(_._2)) }
  }

  def updateOrCreateAll(updaters: K Map (Option[V] => V)): Future[Set[V]] =
    updateOrCreateAll2(updaters.keys.toVector, { (key, v) => updaters(key)(v)})

  def updateOrCreateAll2(keys: Iterable[K], updater: ((K, Option[V]) => V)): Future[Set[V]] =
    if (keys.isEmpty) Future successful Set.empty[V]
    else {
      verbose(s"updateOrCreateAll($keys)")
      getAll(keys) flatMap { values =>
        val loaded: Map[K, Option[V]] = keys.iterator.zip(values.iterator).map { case (k, v) => k -> Option(cache.get(k)).flatten.orElse(v) }.toMap
        val toSave = Vector.newBuilder[V]
        val added = Vector.newBuilder[V]
        val updated = Vector.newBuilder[(V, V)]

        val result = keys .map { key =>
          val current = loaded.get(key).flatten
          val next = updater(key, current)
          current match {
            case Some(c) if c != next =>
              cache.put(key, Some(next))
              toSave += next
              updated += (c -> next)
            case None =>
              cache.put(key, Some(next))
              toSave += next
              added += next
            case Some(_) => // unchanged, ignore
          }
          next
        } .toSet

        val addedResult = added.result
        val updatedResult = updated.result

        returning (db { save(toSave.result)(_) } .future.map { _ => result }) { _ =>
          if (addedResult.nonEmpty) onAdded ! addedResult
          if (updatedResult.nonEmpty) onUpdated ! updatedResult
        }
      }
    }

  private def addInternal(key: K, value: V): Future[V] = {
    cache.put(key, Some(value))
    returning(db { save(Seq(value))(_) } .future.map { _ => value }) { _ =>
      onAdded ! Seq(value)
    }
  }

  protected def updateInternal(key: K, updater: V => V)(current: V): Future[Option[(V, V)]] = {
    val updated = updater(current)
    if (updated == current) Future.successful(Some((current, updated)))
    else {
      cache.put(key, Some(updated))
      returning(db { save(Seq(updated))(_) } .future.map { _ => Some((current, updated)) }) { _ =>
        onUpdated ! Seq((current, updated))
      }
    }
  }

  def put(key: K, value: V): Future[V] = updateOrCreate(key, _ => value, value)

  def getRawCached(key: K): Option[V] = cache.get(key)

  def remove(key: K): Future[Unit] = Future {
    cache.put(key, None)
    returning(db { delete(Seq(key))(_) } .future) { _ => onDeleted ! Seq(key) }
  } .flatten

  def remove(keys: Iterable[K]): Future[Unit] = Future {
    keys foreach { key => cache.put(key, None) }
    returning(db { delete(keys)(_) } .future) { _ => onDeleted ! keys.toVector }
  } .flatten

  def cacheIfNotPresent(key: K, value: V) = cachedOrElse(key, Future {
    Option(cache.get(key)).getOrElse { returning(Some(value))(cache.put(key, _)) }
  })
}
