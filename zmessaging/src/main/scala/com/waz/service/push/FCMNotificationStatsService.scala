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

import com.waz.model.Uid
import com.waz.threading.Threading
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit._

import scala.concurrent.{ExecutionContext, Future}

trait FCMNotificationStatsService {
  def storeNotificationState(id: Uid, stage: String, timestamp: Instant): Future[Unit]
  def getStats: Future[Vector[FCMNotificationStats]]
}

class FCMNotificationStatsServiceImpl(fcmTimestamps: FCMNotificationsRepository,
                                      fcmStats: FCMNotificationStatsRepository)
  extends FCMNotificationStatsService {

  import FCMNotificationStatsService._

  private implicit val ec: ExecutionContext = Threading.Background

  override def storeNotificationState(id: Uid, stage: String, timestamp: Instant): Future[Unit] =
    fcmTimestamps.storeNotificationState(id, stage, timestamp)
      .flatMap { _ =>
        fcmTimestamps.getPreviousStageTime(id, stage).flatMap {
          case Some(prev) =>
            fcmStats.insertOrUpdate(getStageStats(stage, timestamp, prev)).flatMap { _ =>
              if(stage == FCMNotificationsRepository.FinishedPipeline)
                fcmTimestamps.deleteAllWithId(id)
              else Future.successful(())
            }
          case _ => Future.successful(())
        }
      }

  override def getStats: Future[Vector[FCMNotificationStats]] = fcmStats.listAllStats()
}

object FCMNotificationStatsService {

  def getStageStats(stage: String, timestamp: Instant, prev: Instant): FCMNotificationStats = {
    val bucket1 = Instant.from(prev).plus(10, SECONDS)
    val bucket2 = Instant.from(prev).plus(30, MINUTES)

    if (timestamp.isBefore(bucket1)) FCMNotificationStats(stage, 1, 0, 0)
    else if (timestamp.isBefore(bucket2)) FCMNotificationStats(stage, 0, 1, 0)
    else FCMNotificationStats(stage, 0, 0, 1)
  }
}

