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
import com.waz.model.Uid
import com.waz.service.push.FCMNotificationStatsRepository.FCMNotificationStatsDao
import com.waz.threading.CancellableFuture
import com.waz.utils.wrappers.DB
import org.threeten.bp.Instant

import scala.concurrent.ExecutionContext

trait FCMNotificationStatsService {
  def storeNotificationState(id: Uid, stage: String, timestamp: Instant)
                            (implicit ec: ExecutionContext): CancellableFuture[Unit]
  def getStats(implicit db: DB): Vector[FCMNotificationStats]
}

class FCMNotificationStatsServiceImpl(fcmNotRepo: FCMNotificationsRepository)(implicit db: Database)
  extends FCMNotificationStatsService {

  override def storeNotificationState(id: Uid, stage: String, timestamp: Instant)
                                     (implicit ec: ExecutionContext): CancellableFuture[Unit] =
    fcmNotRepo.storeNotificationState(id, stage, timestamp)

  override def getStats(implicit db: DB): Vector[FCMNotificationStats] =
    FCMNotificationStatsDao.list
}

