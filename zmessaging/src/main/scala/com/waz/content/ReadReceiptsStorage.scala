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
import com.waz.model.ReadReceipt.ReadReceiptDao
import com.waz.model.{MessageId, ReadReceipt, UserId}
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.{RefreshingSignal, Signal}
import com.waz.utils.{CachedStorage, CachedStorageImpl, TrimmingLruCache}

import scala.concurrent.{ExecutionContext, Future}

trait ReadReceiptsStorage extends CachedStorage[ReadReceipt.Id, ReadReceipt] {
  def getReceipts(message: MessageId): Future[Seq[ReadReceipt]]
  def receipts(message: MessageId): Signal[Seq[ReadReceipt]]
}

class ReadReceiptsStorageImpl(context: Context, storage: Database) extends CachedStorageImpl[ReadReceipt.Id, ReadReceipt](new TrimmingLruCache(context, Fixed(ReadReceiptsStorage.cacheSize)), storage)(ReadReceiptDao, "ReadReceiptsStorage")
  with ReadReceiptsStorage {

  private implicit val dispatcher: ExecutionContext = new SerialDispatchQueue()

  override def getReceipts(message: MessageId): Future[Seq[ReadReceipt]] =
    find(_.message == message, ReadReceiptDao.findForMessage(message)(_), identity)

  override def receipts(message: MessageId): Signal[Seq[ReadReceipt]] = {
    val changed = onChanged.map(_.filter(_.message == message).map(_.id)).union(onDeleted.map(_.filter(_._1 == message)))
    RefreshingSignal[Seq[ReadReceipt], Seq[ReadReceipt.Id]](getReceipts(message), changed)
  }
}

object ReadReceiptsStorage {
  val cacheSize = 12048
}
