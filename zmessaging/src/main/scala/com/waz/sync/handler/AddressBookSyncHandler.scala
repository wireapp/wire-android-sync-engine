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

import com.waz.Analytics
import com.waz.ZLog._
import com.waz.model.AddressBook
import com.waz.service._
import com.waz.sync.SyncResult
import com.waz.sync.client.AddressBookClient
import com.waz.threading.Threading

import scala.concurrent.Future
import scala.util.control.NoStackTrace

class AddressBookSyncHandler(contacts: ContactsService, client: AddressBookClient) {

  import Threading.Implicits.Background
  private implicit val logTag: LogTag = logTagFor[AddressBookSyncHandler]

  def postAddressBook(ab: AddressBook): Future[SyncResult] = {
    verbose(s"postAddressBook()")
    if (ab == AddressBook.Empty) Future.successful(SyncResult.Success)
    else {
      // TODO: post incremental changes only - once backend supports that
      for {
        postRes <- client.postAddressBook(ab).future
        result <- postRes match {
          case Left(error) =>
            debug(s"postAddressBook failed with error: $error")
            if (error.isFatal) Analytics.saveException(new RuntimeException(s"upload failed: ${error.code}") with NoStackTrace, error.toString)
            Future.successful(SyncResult(error))
          case Right(users) =>
            debug("postAddressBook successful")
            contacts.onAddressBookUploaded(ab, users) map (_ => SyncResult.Success) recover {
              case e: Throwable =>
                Analytics.saveException(e, s"address book upload result processing failed")
                SyncResult.Failure(None, shouldRetry = false)
            }
        }
      } yield result
    }
  }
}
