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

import com.waz.db.Col.{int, text}
import com.waz.db.Dao
import com.waz.utils.wrappers.DBCursor
import com.waz.utils.Identifiable

/**
  * @param stage the stage for which these buckets apply
  * @param bucket1 notifications which were processed in 0-10s
  * @param bucket2 notifications which were processed in 10s-30m
  * @param bucket3 notifications which were processed in 30m+
  */
case class FCMNotificationStats(stage:   String,
                                bucket1: Int,
                                bucket2: Int,
                                bucket3: Int) extends Identifiable[String] {
  override def id: String = stage
}

object FCMNotificationStatsRepository {

  implicit object FCMNotificationStatsDao extends Dao[FCMNotificationStats, String] {
    val Stage = text('stage, "PRIMARY KEY")(_.stage)
    val Bucket1 = int('bucket1)(_.bucket1)
    val Bucket2 = int('bucket2)(_.bucket2)
    val Bucket3 = int('bucket3)(_.bucket3)

    override val idCol = Stage
    override val table = Table("FCMNotificationStats", Stage, Bucket1, Bucket2, Bucket3)

    override def apply(implicit cursor: DBCursor): FCMNotificationStats =
      FCMNotificationStats(Stage, Bucket1, Bucket2, Bucket3)
  }

}

