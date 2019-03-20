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

import com.waz.db.Col.{id, text, timestamp}
import com.waz.db.Dao2
import com.waz.model.Uid
import com.waz.utils.wrappers.DBCursor
import com.waz.utils.Identifiable
import org.threeten.bp.Instant


/**
  * @param receivedAt instant the push notification was received
  * @param stage the stage the notification was in at time `receivedAt`
  */
case class FCMNotification(override val id: Uid,
                           receivedAt:      Instant,
                           stage:           String) extends Identifiable[Uid]

object FCMNotificationsRepository {

  val Pushed = "pushed"
  val Fetched = "fetched"
  val StartedPipeline = "startedPipeline"

  def prevStage(stage: String): Option[String] = stage match {
    case StartedPipeline => Some(Fetched)
    case Fetched => Some(Pushed)
    case _ => None
  }

  implicit object FCMNotificationsDao extends Dao2[FCMNotification, Uid, String] {
    val Id = id[Uid]('_id).apply(_.id)
    val ReceivedAt = timestamp('received_at)(_.receivedAt)
    val Stage = text('stage)(_.stage)

    override val idCol = (Id, Stage)
    override val table = Table("FCMNotifications", Id, ReceivedAt, Stage)

    override def apply(implicit cursor: DBCursor): FCMNotification =
      FCMNotification(Id, ReceivedAt, Stage)
  }

}

