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
package com.waz.zms

import android.content.{Context, Intent}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model.AccountId
import com.waz.utils.returning

import scala.concurrent.Future

class SyncService extends FutureService with ZMessagingService {

  override protected def onIntent(intent: Intent, id: Int): Future[Any] =
    onAccountIntent(intent) { acc =>
      debug(s"onIntent $intent")
      acc.userModule.syncRequests.scheduler.awaitRunning
    }
}

object SyncService {

  def intent(context: Context, user: AccountId) = {
    returning(new Intent(context, classOf[SyncService])) {
      _.putExtra(ZMessagingService.ZmsUserIdExtra, user.str)
    }
  }
}
