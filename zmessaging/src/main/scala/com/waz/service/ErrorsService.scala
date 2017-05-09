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

import android.content.Context
import com.waz.ZLog._
import com.waz.api.ErrorType
import com.waz.content.MessagesStorage
import com.waz.model.ErrorData.ErrorDataDao
import com.waz.model._
import com.waz.content.ZmsDatabase
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.RefreshingSignal
import com.waz.utils.{CachedStorageImpl, TrimmingLruCache}

import scala.collection.{breakOut, mutable}
import scala.concurrent.Future
import com.waz.utils._

class ErrorsService(context: Context, storage: ZmsDatabase, lifecycle: ZmsLifecycle, messages: MessagesStorage) {
  import com.waz.utils.events.EventContext.Implicits.global
  import lifecycle._

  private implicit val dispatcher = new SerialDispatchQueue(name = "ErrorsService")
  private implicit val logTag: LogTag = logTagFor[ErrorsService]

  private var dismissHandler: PartialFunction[ErrorData, Future[_]] = PartialFunction.empty

  val errorsStorage = new CachedStorageImpl[Uid, ErrorData](new TrimmingLruCache(context, Fixed(128)), storage)(ErrorDataDao, "ErrorStorage")

  private val errors = new mutable.HashMap[Uid, ErrorData]()

  private val init = errorsStorage.list() map { es =>
    errors ++= es.map(e => e.id -> e)(breakOut)

    errorsStorage.onChanged.on(dispatcher) { es =>
      errors ++= es.map(e => e.id -> e)(breakOut)
    }

    errorsStorage.onDeleted.on(dispatcher) { errors --= _ }

    errors
  }

  val onChanged = errorsStorage.onChanged.map(_ => System.currentTimeMillis()).union(errorsStorage.onDeleted.map(_ => System.currentTimeMillis()))

  def onErrorDismissed(handler: PartialFunction[ErrorData, Future[_]]) = dispatcher {
    dismissHandler = dismissHandler.orElse(handler)
  }

  def getErrors = new RefreshingSignal[Vector[ErrorData], Long](CancellableFuture { errors.values.toVector.sortBy(_.time) }, onChanged)

  def dismissError(id: Uid) =
    storage { ErrorDataDao.getById(id)(_) }
      .future.flatMap {
        case Some(error) => dismissed(error) flatMap { _ => delete(error) }
        case _ =>
          warn(s"no error found with id: $id")
          Future.successful(0)
      }

  def dismissAllErrors() = errorsStorage.list() flatMap { errors =>
    Future.sequence(errors map dismissed) flatMap { _ => delete(errors: _*) }
  }

  private def dismissed(error: ErrorData) = Future {
    dismissHandler.applyOrElse(error, { (e: ErrorData) => Future.successful(e) })
  }.flatten

  private def delete(errors: ErrorData*) = {
    verbose(s"delete: $errors")
    errorsStorage.remove(errors.map(_.id))
  }

  def addErrorWhenActive(error: ErrorData) =
    if (isUiActive) errorsStorage.insert(error)
    else dismissed(error)

  def addError(error: ErrorData) = errorsStorage.insert(error)

  def addAssetTooLargeError(convId: ConvId, messageId: MessageId) =
    addError(ErrorData(Uid(), ErrorType.CANNOT_SEND_ASSET_TOO_LARGE, convId = Some(convId), messages = Seq(messageId)))

  def addAssetFileNotFoundError(assetId: AssetId) = messages.get(MessageId(assetId.str)) flatMapOpt { msg =>
    addError(ErrorData(Uid(), ErrorType.CANNOT_SEND_ASSET_FILE_NOT_FOUND, convId = Some(msg.convId), messages = Seq(msg.id))) map { Some(_) }
  }

  def addConvUnverifiedError(conv: ConvId, message: MessageId) = {
    def matches(err: ErrorData) =
      err.convId.contains(conv) && err.errType == ErrorType.CANNOT_SEND_MESSAGE_TO_UNVERIFIED_CONVERSATION

    init flatMap { _ =>
      val err = errors.find(p => matches(p._2)).fold {
        ErrorData(Uid(), ErrorType.CANNOT_SEND_MESSAGE_TO_UNVERIFIED_CONVERSATION, convId = Some(conv), messages = Seq(message))
      } {
        case (_, e) => e.copy(messages = e.messages :+ message)
      }
      errors += (err.id -> err)
      errorsStorage.put(err.id, err)
    }
  }
}
