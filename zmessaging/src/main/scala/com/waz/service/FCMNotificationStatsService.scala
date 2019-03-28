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
package com.waz.service

import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.{FCMNotification, Uid}
import com.waz.repository.{FCMNotificationStats, FCMNotificationStatsRepository, FCMNotificationsRepository}
import com.waz.threading.Threading
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit._

import scala.concurrent.{ExecutionContext, Future}

trait FCMNotificationStatsService {
  def getStats: Future[Vector[FCMNotificationStats]]
  def markNotificationsWithState(ids: Set[Uid], stage: String): Future[Unit]
}

class FCMNotificationStatsServiceImpl(fcmTimestamps: FCMNotificationsRepository,
                                      fcmStats: FCMNotificationStatsRepository)
    extends FCMNotificationStatsService with DerivedLogTag {

  import FCMNotificationStatsService._
  import scala.async.Async._
  import com.waz.log.LogSE._
  import FCMNotification.Pushed

  private implicit val ec: ExecutionContext = Threading.Background

  override def markNotificationsWithState(ids: Set[Uid], stage: String): Future[Unit] =  async {
    stage match {
      case Pushed => await {
        Future.traverse(ids.toSeq)(p => fcmTimestamps.storeNotificationState(p, stage, Instant.now))
      }
      case _ =>
          val fcmIds = await { filterFCMNotifications(ids) }
          verbose(l"""marking ${showString(fcmIds.size.toString)} notifications
               at stage ${showString(stage)} """)
          await { calcAndStore(ids, stage) }
    }
  }

  private def calcAndStore(ids: Set[Uid], stage: String): Future[Unit] = Future.traverse(ids){ id =>
    fcmTimestamps.getPreviousStageTime(id, stage).flatMap {
      case Some(prev) =>
        verbose(l"storing timestamp for stage ${showString(stage)} ")
        val timestamp = Instant.now()
        val newStage = FCMNotification(id, stage, timestamp)
        fcmStats.writeTimestampAndStats(getStageStats(stage, timestamp, prev), newStage)
      case None => Future.successful(())
    }}.map(_ => ())

  override def getStats: Future[Vector[FCMNotificationStats]] = fcmStats.listAllStats()

  private def filterFCMNotifications(ids: Set[Uid]): Future[Set[Uid]] = fcmTimestamps.exists(ids)
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
