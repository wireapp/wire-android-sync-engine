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
package com.waz.sync.client

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model._
import com.waz.service.BackendConfig
import com.waz.service.tracking.TrackingService.NoReporting
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet2.AuthRequestInterceptor
import com.waz.znet2.http._
import org.json.{JSONArray, JSONObject}

import scala.concurrent.Future
import scala.util.Right

trait UsersClient {
  def loadUsers(ids: Seq[UserId]): ErrorOrResponse[Seq[UserInfo]]
  def loadSelf(): ErrorOrResponse[UserInfo]
  def updateSelf(info: UserInfo): ErrorOrResponse[Unit]
  def deleteAccount(password: Option[String] = None): ErrorOr[Unit]
  def setSearchable(searchable: Boolean): ErrorOrResponse[Unit]
}

class UsersClientImpl(implicit
                      backendConfig: BackendConfig,
                      httpClient: HttpClient,
                      authRequestInterceptor: AuthRequestInterceptor) extends UsersClient {

  import BackendConfig.backendUrl
  import HttpClient.dsl._
  import Threading.Implicits.Background
  import com.waz.sync.client.UsersClient._

  private implicit val UsersResponseDeserializer: RawBodyDeserializer[Seq[UserInfo]] =
    RawBodyDeserializer[JSONArray].map(json => JsonDecoder.array[UserInfo](json))

  override def loadUsers(ids: Seq[UserId]): ErrorOrResponse[Seq[UserInfo]] = {
    if (ids.isEmpty) CancellableFuture.successful(Right(Vector()))
    else {
      val result = Future.traverse(ids.grouped(IdsCountThreshold).toSeq) { ids => // split up every IdsCountThreshold user ids so that the request uri remains short enough
        Request.Get(url = backendUrl(UsersPath), queryParameters = queryParameters("ids" -> ids.mkString(",")))
          .withResultType[Seq[UserInfo]]
          .withErrorType[ErrorResponse]
          .execute
      }.map(_.flatten).map(Right(_)).recover { case e: ErrorResponse => Left(e) }

      CancellableFuture.lift(result)
    }
  }

  override def loadSelf(): ErrorOrResponse[UserInfo] = {
    Request.Get(url = backendUrl(SelfPath))
      .withResultType[UserInfo]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def updateSelf(info: UserInfo): ErrorOrResponse[Unit] = {
    verbose(s"updateSelf: $info, picture: ${info.picture}")
    Request.Put(url = backendUrl(SelfPath), body = info)
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def deleteAccount(password: Option[String] = None): ErrorOr[Unit] = {
    Request.Delete(url = backendUrl(SelfPath), body = DeleteAccount(password))
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
      .future
  }

  override def setSearchable(searchable: Boolean): ErrorOrResponse[Unit] = {
    Request.Put(url = backendUrl(SearchablePath), body = JsonEncoder(_.put("searchable", searchable)))
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

}

object UsersClient {
  val UsersPath = "/users"
  val SelfPath = "/self"
  val ConnectionsPath = "/self/connections"
  val SearchablePath = "/self/searchable"
  val IdsCountThreshold = 64

  case class DeleteAccount(password: Option[String])

  implicit lazy val DeleteAccountEncoder: JsonEncoder[DeleteAccount] = new JsonEncoder[DeleteAccount] {
    override def apply(v: DeleteAccount): JSONObject = JsonEncoder { o =>
      v.password foreach (o.put("password", _))
    }
  }

  class FailedLoadUsersResponse(val error: ErrorResponse)
    extends RuntimeException(s"loading users failed with: $error") with NoReporting

}
