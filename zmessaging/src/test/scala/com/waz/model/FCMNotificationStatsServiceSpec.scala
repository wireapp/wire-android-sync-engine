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

import com.waz.service.push.{FCMNotificationStats, FCMNotificationStatsService, FCMNotificationsRepository}
import com.waz.specs.AndroidFreeSpec
import com.waz.threading.Threading
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit._

class FCMNotificationStatsServiceSpec extends AndroidFreeSpec {

  implicit val ec = Threading.Background
  import FCMNotificationsRepository._
  import FCMNotificationStatsService.updateBucketWithNotification

  val stage = Pushed
  val bucket1 = FCMNotificationStats(stage, 1, 0, 0)
  val bucket2 = FCMNotificationStats(stage, 0, 1, 0)
  val bucket3 = FCMNotificationStats(stage, 0, 0, 1)

  private def runScenario(offset: Long) = {
    val stageTime = Instant.now
    //bucket1 limit is 10s
    val prev = Instant.from(stageTime).minus(offset, MILLIS)
    val curRow = FCMNotificationStats(stage, 0, 0, 0)

    updateBucketWithNotification(curRow, stageTime, stage, prev)
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

}
