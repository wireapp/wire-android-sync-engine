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

import com.google.firebase.iid.{FirebaseInstanceId, FirebaseInstanceIdService}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.service.ZMessaging
import com.waz.threading.Threading

class InstanceIdListenerService extends FirebaseInstanceIdService with ZMessagingService {
  import Threading.Implicits.Background

  override def onTokenRefresh(): Unit = {
    val token = Option(FirebaseInstanceId.getInstance().getToken())
    info(s"onTokenRefresh() called, got token: $token")

    token.foreach { t =>
      ZMessaging.currentAccounts.getCurrentZms.map {
        case Some(zms) => zms.pushToken.onTokenRefresh ! t
        case None =>
      }
    }
  }
}
