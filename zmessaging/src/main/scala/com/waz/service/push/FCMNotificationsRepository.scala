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

import com.waz.content.Database
import com.waz.db.Col.{id, text, timestamp}
import com.waz.db.Dao2
import com.waz.model.Uid
import com.waz.service.push.FCMNotificationStatsRepository.FCMNotificationStatsDao
import com.waz.threading.CancellableFuture
import com.waz.utils.wrappers.{DB, DBCursor}
import com.waz.utils.Identifiable
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit._

import scala.concurrent.ExecutionContext


/**
  * @param stageStartTime instant the push notification was received at stage
  * @param stage the stage the notification was in at time `receivedAt`
  */
case class FCMNotification(override val id: Uid,
                           stageStartTime:      Instant,
                           stage:           String) extends Identifiable[Uid]

class FCMNotificationsRepository(implicit db: Database) {

  import FCMNotificationsRepository._

  private def getPreviousStageTime(id: Uid, stage: String)(implicit db: DB): Option[Instant] = {
    FCMNotificationsRepository
      .prevStage(stage)
      .flatMap { s => FCMNotificationsDao.getById(id, s).map(_.stageStartTime) }
  }

  def storeNotificationState(id: Uid, stage: String, timestamp: Instant)
                                     (implicit ec: ExecutionContext): CancellableFuture[Unit] =
  db.withTransaction { implicit db =>
    FCMNotificationsDao.insertOrIgnore(FCMNotification(id, timestamp, stage))
    getPreviousStageTime(id, stage).foreach { prev =>
      FCMNotificationStatsDao.getById(stage) match {
        case Some(stageRow) =>
          FCMNotificationStatsDao
            .insertOrReplace(getNewStageStats(stageRow, timestamp, stage, prev))
          if(stage == FinishedPipeline) {
            FCMNotificationsDao
              .deleteEvery(Seq(Pushed, Fetched, StartedPipeline, FinishedPipeline)
                .map(s => (id, s)))
          }
        case _ =>
          FCMNotificationStatsDao.insertOrReplace(
            getNewStageStats(FCMNotificationStats(stage, 0, 0, 0), timestamp, stage, prev))
      }
    }
  }.map(_ => ())
}

object FCMNotificationsRepository {

  val Pushed = "pushed"
  val Fetched = "fetched"
  val StartedPipeline = "startedPipeline"
  val FinishedPipeline = "finishedPipeline"

  //this method only exists to facilitate testing
  def getNewStageStats(curStats: FCMNotificationStats, stageTimestamp: Instant,
                       stage: String, prevStageTime: Instant): FCMNotificationStats = {
    val bucket1 = Instant.from(prevStageTime).plus(10, SECONDS)
    val bucket2 = Instant.from(prevStageTime).plus(30, MINUTES)
    if (stageTimestamp.isBefore(bucket1))
      curStats.copy(stage, bucket1 = curStats.bucket1 + 1)
    else if (stageTimestamp.isBefore(bucket2))
      curStats.copy(stage, bucket2 = curStats.bucket2 + 1)
    else curStats.copy(stage, bucket3 = curStats.bucket3 + 1)
  }

  def prevStage(stage: String): Option[String] = stage match {
    case StartedPipeline => Some(Fetched)
    case Fetched => Some(Pushed)
    case _ => None
  }

  implicit object FCMNotificationsDao extends Dao2[FCMNotification, Uid, String] {
    val Id = id[Uid]('_id).apply(_.id)
    val stageStartTime = timestamp('stage_start_time)(_.stageStartTime)
    val Stage = text('stage)(_.stage)

    override val idCol = (Id, Stage)
    override val table = Table("FCMNotifications", Id, stageStartTime, Stage)

    override def apply(implicit cursor: DBCursor): FCMNotification =
      FCMNotification(Id, stageStartTime, Stage)
  }

}

