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
import com.waz.content.ContentChange.{Added, Removed, Updated}
import com.waz.model.NotificationData.NotificationDataDao
import com.waz.model.{NotId, NotificationData}
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.{AggregatingSignal, EventStream}
import com.waz.utils.{CachedStorageImpl, TrimmingLruCache}

import scala.collection._

class NotificationStorage(context: Context, storage: Database) extends CachedStorageImpl[NotId, NotificationData](new TrimmingLruCache(context, Fixed(128)), storage)(NotificationDataDao, "NotificationStorage") {
  import com.waz.threading.Threading.Implicits.Background

  val changesStream = EventStream.union[Seq[ContentChange[NotId, NotificationData]]](
    onAdded.map(_.map(d => Added(d.id, d))),
    onUpdated.map(_.map { case (prv, curr) => Updated(prv.id, prv, curr) }),
    onDeleted.map(_.map(Removed(_)))
  )

  // signal with all data
  val notifications = new AggregatingSignal[Seq[ContentChange[NotId, NotificationData]], Map[NotId, NotificationData]](changesStream, list().map(_.map { n => n.id -> n }(breakOut)), { (values, changes) =>
    val added = new mutable.HashMap[NotId, NotificationData]
    val removed = new mutable.HashSet[NotId]
    changes foreach {
      case Added(id, data) =>
        removed -= id
        added += id -> data
      case Updated(id, _, data) =>
        removed -= id
        added += id -> data
      case Removed(id) =>
        removed += id
        added -= id
    }
    values -- removed ++ added
  })
}
