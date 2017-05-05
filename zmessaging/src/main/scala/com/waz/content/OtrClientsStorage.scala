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
package com.waz.content

import android.content.Context
import com.waz.api.Verification
import com.waz.model.UserId
import com.waz.model.otr.{Client, ClientId, UserClients}
import com.waz.model.otr.UserClients.UserClientsDao
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.Signal
import com.waz.utils.{CachedStorageImpl, TrimmingLruCache}

class OtrClientsStorage(context: Context, storage: Database) extends CachedStorageImpl[UserId, UserClients](new TrimmingLruCache(context, Fixed(2000)), storage)(UserClientsDao, "OtrClientsStorage") {
  import com.waz.threading.Threading.Implicits.Background

  def incomingClientsSignal(userId: UserId, clientId: ClientId): Signal[Seq[Client]] =
    signal(userId) map { ucs =>
      ucs.clients.get(clientId).flatMap(_.regTime).fold(Seq.empty[Client]) { current =>
        ucs.clients.values.filter(c => c.verified == Verification.UNKNOWN && c.regTime.exists(_.isAfter(current))).toVector
      }
    }

  def getClients(user: UserId) = get(user).map(_.fold(Seq.empty[Client])(_.clients.values.toVector))

  def updateVerified(userId: UserId, clientId: ClientId, verified: Boolean) = update(userId, { uc =>
    uc.clients.get(clientId) .fold (uc) { client =>
      uc.copy(clients = uc.clients + (client.id -> client.copy(verified = if (verified) Verification.VERIFIED else Verification.UNVERIFIED)))
    }
  })
}
