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

import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model.otr.ClientId
import com.waz.model.{AccountId, GcmId}
import com.waz.service.BackendConfig
import com.waz.service.push.GcmGlobalService.{GcmNotAvailableException, GcmRegistration}
import com.waz.service.push.{GcmService, PushTokenService}
import com.waz.sync.SyncResult
import com.waz.sync.client.GcmClient
import com.waz.sync.client.GcmClient.GcmToken
import com.waz.threading.{CancellableFuture, Threading}

import scala.concurrent.Future

class GcmSyncHandler(user: AccountId, gcmService: GcmService, pushTokenService: PushTokenService, backend: BackendConfig, clientId: ClientId, client: GcmClient) {

  import Threading.Implicits.Background
  private implicit val tag: LogTag = logTagFor[GcmSyncHandler]

  def resetGcm(): Future[SyncResult] = {
    def post(token: String) =
      client.postPushToken(GcmToken(token, gcmService.gcmSenderId, clientId))

    gcmService.resetGcm(r => post(r.token).map(_.isRight))
      .map {
        case Some(GcmRegistration(_, `user`, _)) => SyncResult.Success
        case _ => SyncResult.Failure(None)
      }
      .recover {
        case e: GcmNotAvailableException => SyncResult.Failure(None, shouldRetry = false)
      }
  }

  def registerPushToken(): Future[SyncResult] = {
    pushTokenService.currentTokenPref().flatMap {
      case Some(token) =>
        client.postPushToken(GcmToken(token, backend.gcmSenderId, clientId)).future.flatMap {
          case Right(GcmToken(`token`, _, `clientId`, _)) => pushTokenService.onTokenRegistered().map(_ => SyncResult.Success)
          case Right(_)  => Future.successful(SyncResult.Failure(None, shouldRetry = false))
          case Left(err) => Future.successful(SyncResult.Failure(Some(err)))
        }
      case None => Future.successful(SyncResult.Failure(None, shouldRetry = false))
    }
  }

  def deleteGcmToken(token: GcmId): CancellableFuture[SyncResult] = {
    debug(s"deleteGcmToken($token)")
    client.deletePushToken(token.str) map { _.fold(SyncResult(_), _ => SyncResult.Success) }
  }
}
