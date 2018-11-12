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
package com.waz.sync.handler

import com.waz.model.Uid
import com.waz.model.otr.ClientId
import com.waz.service.push.PushNotificationService
import com.waz.sync.SyncResult
import com.waz.sync.client.PushNotificationsClient
import com.waz.sync.client.PushNotificationsClient.LoadNotificationsResult
import com.waz.threading.Threading
import com.waz.ZLog.ImplicitTag._

import scala.concurrent.Future

class PushNotificationSyncHandler(clientId: ClientId,
                                  client:   PushNotificationsClient,
                                  service:  PushNotificationService) {

  import Threading.Implicits.Background

  /**
    * @param trigger the push notification id (if any) that was responsible for triggering this call
    * @return
    */
  def syncNotifications(trigger: Option[Uid]): Future[SyncResult] = {

    def load(lastId: Option[Uid], firstSync: Boolean = false, attempts: Int = 0): Future[SyncResult] =
      (lastId match {
        case None => if (firstSync) client.loadLastNotification(clientId) else client.loadNotifications(None, clientId)
        case id   => client.loadNotifications(id, clientId)
      }).future.flatMap {
        case Right(LoadNotificationsResult(response, historyLost)) if response.hasMore =>
          service.onNotificationsResponse(response.notifications, trigger, response.beTime, firstSync = firstSync, historyLost)
            .flatMap(_ => load(response.notifications.lastOption.map(_.id)))

        case Right(LoadNotificationsResult(response, historyLost)) =>
          service.onNotificationsResponse(response.notifications, trigger, response.beTime, firstSync = firstSync, historyLost)
            .map(_ => SyncResult.Success)

        case Left(err) =>
          Future.successful(SyncResult(err))
      }

    service.lastKnownNotificationId.head.flatMap { since =>
      load(since, firstSync = since.isEmpty)
    }
  }

  def processNotifications(): Future[SyncResult] =
    service.processNotifications().map(_ => SyncResult.Success)

}
