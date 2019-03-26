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
package com.waz.model

import com.waz.repository.{FCMNotificationStats, FCMNotificationStatsRepository, FCMNotificationsRepository}
import com.waz.service.FCMNotificationStatsServiceImpl
import com.waz.specs.AndroidFreeSpec
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit._

import scala.concurrent.Future

class FCMNotificationStatsServiceSpec extends AndroidFreeSpec {

  import FCMNotification.{Pushed, FinishedPipeline}
  import com.waz.service.FCMNotificationStatsService._

  private val fcmTimeStamps = mock[FCMNotificationsRepository]
  private val fcmStats = mock[FCMNotificationStatsRepository]

  private val stage = Pushed
  private val bucket1 = FCMNotificationStats(stage, 1, 0, 0)
  private val bucket2 = FCMNotificationStats(stage, 0, 1, 0)
  private val bucket3 = FCMNotificationStats(stage, 0, 0, 1)

  private def runScenario(offset: Long) = {
    val stageTime = Instant.now
    //bucket1 limit is 10s
    val prev = Instant.from(stageTime).minus(offset, MILLIS)

    getStageStats(stage, stageTime, prev)
  }

  scenario("Notifications in bucket1 are sorted correctly") {
    runScenario(9999) shouldEqual bucket1
  }

  scenario("Notifications in bucket2 are sorted correctly") {
    runScenario(10001) shouldEqual bucket2
  }

  scenario("Notifications in bucket3 are sorted correctly") {
    runScenario(1000*60*30+1) shouldEqual bucket3 //30 mins + 1 millisecond
  }

  scenario("Stats aren't updated if there is no previous stage") {
    val id = Uid("test")
    val service = getService()
    (fcmTimeStamps.storeNotificationState _).expects(*, *, *).once().returning(Future.successful(()))
    (fcmTimeStamps.getPreviousStageTime _).expects(*, *).once().returning(Future.successful(None))

    result(service.storeNotificationState(id, stage, Instant.now()))
  }

  scenario("Stats are updated if there is a previous stage, and current stage isn't final") {
    val id = Uid("test")
    val service = getService()
    (fcmTimeStamps.storeNotificationState _).expects(*, *, *).once().returning(Future.successful(()))
    (fcmTimeStamps.getPreviousStageTime _).expects(*, *).once().returning(Future.successful(Some(Instant.now())))
    (fcmStats.insertOrUpdate _).expects(*).once().returning(Future.successful(()))

    result(service.storeNotificationState(id, stage, Instant.now()))
  }

  scenario("Previous timestamp rows are deleted when we have reached final stage") {
    val id = Uid("test")
    val service = getService()
    (fcmTimeStamps.storeNotificationState _).expects(*, *, *).once().returning(Future.successful(()))
    (fcmTimeStamps.getPreviousStageTime _).expects(*, *).once().returning(Future.successful(Some(Instant.now())))
    (fcmStats.insertOrUpdate _).expects(*).once().returning(Future.successful(()))
    (fcmTimeStamps.deleteAllWithId _).expects(*).once().returning(Future.successful(()))

    result(service.storeNotificationState(id, FinishedPipeline, Instant.now()))
  }

  def getService() = new FCMNotificationStatsServiceImpl(fcmTimeStamps, fcmStats)
}
