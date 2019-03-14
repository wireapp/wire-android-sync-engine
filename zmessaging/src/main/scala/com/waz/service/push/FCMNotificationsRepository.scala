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
package com.waz.service.push

import android.content.Context
import com.waz.content.Database
import com.waz.db.Col.{id, text, timestamp}
import com.waz.db.Dao
import com.waz.model.Uid
import com.waz.service.push.FCMNotification.FCMNotificationsRepositoryDao
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.wrappers.DBCursor
import com.waz.utils.{CachedStorage, CachedStorageImpl, Identifiable, TrimmingLruCache}
import org.threeten.bp.Instant


trait FCMNotificationsRepository extends CachedStorage[Uid, FCMNotification]

class FCMNotificationsRepositoryImpl(context: Context, storage: Database)
  extends CachedStorageImpl[Uid, FCMNotification](new TrimmingLruCache(context, Fixed(100)), storage)(FCMNotificationsRepositoryDao)
    with FCMNotificationsRepository

/**
  * @param receivedAt instant the push notification was received
  * @param stage the stage the notification was in at time `receivedAt`
  */
case class FCMNotification(override val id: Uid,
                           receivedAt:      Instant,
                           stage:           String) extends Identifiable[Uid]

object FCMNotification {

  val Pushed = "pushed"
  val Fetched = "fetched"
  val StartOfPipeline = "StartOfPipeline"
  val EndOfPipeline = "EndOfPipeline"

  implicit object FCMNotificationsRepositoryDao extends Dao[FCMNotification, Uid] {
    val Id = id[Uid]('_id, "PRIMARY KEY").apply(_.id)
    val ReceivedAt = timestamp('received_at)(_.receivedAt)
    val Stage = text('stage)(_.stage)

    override val idCol = Id
    override val table = Table("FCMNotifications", Id, ReceivedAt, Stage)

    override def apply(implicit cursor: DBCursor): FCMNotification =
      FCMNotification(Id, ReceivedAt, Stage)
  }

}

