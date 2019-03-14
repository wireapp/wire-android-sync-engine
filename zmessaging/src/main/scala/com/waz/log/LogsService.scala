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
package com.waz.log

import com.waz.content.UserPreferences
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.UserId
import com.waz.service.AccountsService
import com.waz.utils.events.Signal

import scala.concurrent.{ExecutionContext, Future}

trait LogsService {
  def logsEnabledGlobally: Signal[Boolean]
  def logsEnabled(userId: UserId): Future[Boolean]
  def setLogsEnabled(userId: UserId, enabled: Boolean): Future[Unit]
}

//TODO Think about cycle dependency between LogsService and InternalLog
class LogsServiceImpl(accountsService: AccountsService)(implicit ec: ExecutionContext)
  extends LogsService with DerivedLogTag {

  import com.waz.utils.events.EventContext.Implicits._

  override val logsEnabledGlobally: Signal[Boolean] =
    for {
      zmss <- accountsService.zmsInstances
      prefSignals = zmss.map(_.userPrefs(UserPreferences.LogsEnabled).signal)
      prefs <- Signal.sequence(prefSignals.toSeq: _*)
    } yield prefs.forall(identity)

  logsEnabledGlobally.ifFalse.apply { _ =>
    InternalLog.clearAll()
  }

  override def logsEnabled(userId: UserId): Future[Boolean] =
    for {
      zmss <- accountsService.zmsInstances.head
      zms = zmss.filter(_.selfUserId == userId).head
      enabled <- zms.userPrefs(UserPreferences.LogsEnabled).apply()
    } yield enabled

  override def setLogsEnabled(userId: UserId, enabled: Boolean): Future[Unit] =
    for {
      zmss <- accountsService.zmsInstances.head
      zms = zmss.filter(_.selfUserId == userId).head
      _ <- zms.userPrefs(UserPreferences.LogsEnabled) := enabled
    } yield ()

}
