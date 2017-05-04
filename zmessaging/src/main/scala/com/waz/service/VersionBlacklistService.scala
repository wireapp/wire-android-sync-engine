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

import com.waz.ZLog._
import com.waz.model.VersionBlacklist
import com.waz.service.VersionBlacklistService._
import com.waz.sync.client.VersionBlacklistClient
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}

import scala.concurrent.Future
import scala.concurrent.duration._

class VersionBlacklistService(metadata: MetaDataService, prefs: PreferenceServiceImpl, client: VersionBlacklistClient) {

  import Threading.Implicits.Background
  private implicit val tag: LogTag = logTagFor[VersionBlacklistService]
  private implicit val ec = EventContext.Global
  import metadata._

  val lastUpToDateSync = prefs.preference[Long](LastUpToDateSyncPref, 0)
  val lastCheckedVersion = prefs.preference[Int](LastCheckedVersionPref, 0)
  val upToDatePref = prefs.preference[Boolean](UpToDatePref, defaultValue = true)

  val upToDate = Signal(lastCheckedVersion.signal, upToDatePref.signal) map {
    case (lastVersion, isUpToDate) => lastVersion != metadata.appVersion || isUpToDate
  }

  // check if needs syncing, and try doing that immediately
  shouldSync foreach {
    case true => syncVersionBlackList()
    case false => // no need
  }

  def shouldSync = for {
    lastSyncTime <- lastUpToDateSync()
    lastVersion <- lastCheckedVersion()
    isUpToDate <- upToDatePref()
  } yield {
    lastVersion != metadata.appVersion || (System.currentTimeMillis - lastSyncTime).millis > 1.day
  }

  def syncVersionBlackList() = client.loadVersionBlacklist().future flatMap { blacklist =>
    debug(s"Retrieved version blacklist: $blacklist")
    updateBlacklist(blacklist.right.getOrElse(VersionBlacklist()))
  }

  def updateBlacklist(blacklist: VersionBlacklist): Future[Unit] = {
    verbose(s"app Version: $appVersion, blacklist: $blacklist")
    for {
      _ <- upToDatePref := (appVersion >= blacklist.oldestAccepted && !blacklist.blacklisted.contains(appVersion))
      _ <- lastUpToDateSync := System.currentTimeMillis
      _ <- lastCheckedVersion := appVersion
    } yield ()
  }
}

object VersionBlacklistService {
  val UpToDatePref = "UpToDate"
  val LastUpToDateSyncPref = "LastUpToDateSync"
  val LastCheckedVersionPref = "UpToDateVersion"
}
