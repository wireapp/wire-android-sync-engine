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

import com.waz.api.NetworkMode
import com.waz.model.Uid
import com.waz.service.otr.OtrService
import com.waz.service.{EventPipeline, NetworkModeService, UiLifeCycle}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.client.PushNotificationEncoded
import com.waz.sync.{SyncRequestService, SyncServiceHandle}
import com.waz.testutils.{TestGlobalPreferences, TestUserPreferences}
import com.waz.utils.events.{EventStream, Signal}

import scala.concurrent.Future

class PushNotificationServiceSpec extends AndroidFreeSpec {

  val userPrefs   = new TestUserPreferences
  val globalPrefs = new TestGlobalPreferences
  val receivedPushes        = mock[ReceivedPushStorage]
  val notificationsStorage  = mock[PushNotificationEventsStorage]
  val pipline               = mock[EventPipeline]
  val otrService            = mock[OtrService]
  val wsPushService         = mock[WSPushService]
  val network               = mock[NetworkModeService]
  val lifeCycle             = mock[UiLifeCycle]
  val syncService           = mock[SyncRequestService]
  val sync                  = mock[SyncServiceHandle]

  val wsNotifications = EventStream[Seq[PushNotificationEncoded]]()
  val wsConnected     = Signal(false)
  val networkMode     = Signal(NetworkMode.WIFI)

//  scenario("receive notifications from sync") {
//    val nots = Seq(Uid(), Uid()).map(id => PushNotificationEncoded(id)).toVector
//
//    (notificationsStorage.saveAll _).expects(nots).once().returning(Future.successful({}))
//    await(getService.onNotificationsResponse(nots, Some(Uid()), Some(clock.instant()), false, false))
//  }

  def getService = {

    (wsPushService.notifications _).expects().anyNumberOfTimes().returning(wsNotifications)
    (wsPushService.connected _).expects().anyNumberOfTimes().returning(wsConnected)
    (network.networkMode _).expects().anyNumberOfTimes().returning(networkMode)

    new PushNotificationServiceImpl(account1Id, userPrefs, globalPrefs, receivedPushes, notificationsStorage, pipline,
      otrService, wsPushService, network, lifeCycle, tracking, syncService, sync)
  }



}
