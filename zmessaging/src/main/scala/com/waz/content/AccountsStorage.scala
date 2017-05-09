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
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.{Credentials, EmailCredentials, PhoneCredentials}
import com.waz.model.AccountData.AccountDataDao
import com.waz.model._
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.{CachedStorage, CachedStorageImpl, TrimmingLruCache}

import scala.concurrent.Future

trait AccountsStorage extends CachedStorage[AccountId, AccountData] {
  def findByEmail(email: EmailAddress): Future[Option[AccountData]]
  def findByPhone(phone: PhoneNumber): Future[Option[AccountData]]
  def find(credentials: Credentials): Future[Option[AccountData]]
}

class AccountsStorageImpl(context: Context, storage: Database) extends CachedStorageImpl[AccountId, AccountData](new TrimmingLruCache(context, Fixed(8)), storage)(AccountDataDao) with AccountsStorage {
  import com.waz.threading.Threading.Implicits.Background

  def findByEmail(email: EmailAddress) = find(_.email.contains(email), AccountDataDao.findByEmail(email)(_), identity).map(_.headOption)

  def findByPhone(phone: PhoneNumber) = find(_.phone.contains(phone), AccountDataDao.findByPhone(phone)(_), identity).map(_.headOption)

  def find(credentials: Credentials): Future[Option[AccountData]] = credentials match {
    case EmailCredentials(email, _, _) => findByEmail(email)
    case PhoneCredentials(phone, _, _) => findByPhone(phone)
    case _ => Future successful None
  }
}
