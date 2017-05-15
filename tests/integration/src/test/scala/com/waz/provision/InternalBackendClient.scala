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
package com.waz.provision

import android.util.Base64
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.build.InternalCredentials
import com.waz.model._
import com.waz.service.BackendConfig
import com.waz.threading.CancellableFuture
import com.waz.utils.wrappers.URI
import com.waz.utils.JsonEncoder
import com.waz.znet.ContentEncoder.JsonContentEncoder
import com.waz.znet.Response.SuccessHttpStatus
import com.waz.znet.ZNetClient._
import com.waz.znet._

import scala.util.Try

class InternalBackendClient(client: AsyncClient, backend: BackendConfig) {
  private implicit val logTag: LogTag = logTagFor[InternalBackendClient]

  import scala.concurrent.ExecutionContext.Implicits.global

  val (user, password) = InternalCredentials.backend(backend)

  def activateEmail(email: EmailAddress): ErrorOrResponse[Unit] = {
    val request = Request.Get("/i/users/activation-code", headers = basicAuthHeader, timeout = AsyncClient.DefaultTimeout)
    client(URI.parse(Request.query(backend.baseUrl + "/i/users/activation-code", "email" -> email.str)), request) flatMap {
      case Response(SuccessHttpStatus(), CodeExtractor(code), headers) => verifyEmail(email, ConfirmationCode(code))
      case Response(_, ErrorResponse(status, message, label), _) => CancellableFuture.successful(Left(ErrorResponse(status, message, label)))
      case other => CancellableFuture.successful(Left(ErrorResponse(other.status.status, other.toString, "unknown")))
    }
  }

  def getPhoneActivationCode(phone: PhoneNumber): ErrorOrResponse[ConfirmationCode] = getPhoneConfirmationCode(phone, "activation")
  def getPhoneLoginCode(phone: PhoneNumber): ErrorOrResponse[ConfirmationCode] = getPhoneConfirmationCode(phone, "login")

  private def verifyEmail(email: EmailAddress, code: ConfirmationCode): ErrorOrResponse[Unit] = {
    val request = Request.Post("/activate", data = verifyRequestBody(email, code), timeout = AsyncClient.DefaultTimeout)
    client(URI.parse(backend.baseUrl + "/activate"), request) map {
      case Response(SuccessHttpStatus(), _, _) => Right(())
      case Response(_, ErrorResponse(status, msg, label), _) => Left(ErrorResponse(status, msg, label))
      case other => Left(ErrorResponse(other.status.status, other.toString, "unknown"))
    }
  }

  private def getPhoneConfirmationCode(phone: PhoneNumber, kindOfCode: String): ErrorOrResponse[ConfirmationCode] = {
    val request = Request.Get(s"/i/users/$kindOfCode-code", headers = basicAuthHeader, timeout = AsyncClient.DefaultTimeout)
    client(URI.parse(Request.query(backend.baseUrl + s"/i/users/$kindOfCode-code", "phone" -> phone.str)), request) map {
      case Response(SuccessHttpStatus(), CodeExtractor(code), headers) => Right(ConfirmationCode(code))
      case Response(_, ErrorResponse(status, message, label), _) => Left(ErrorResponse(status, message, label))
      case other => Left(ErrorResponse(other.status.status, other.toString, "unknown"))
    }
  }

  def getInvitationToken(inviter: UserId, invitation: InvitationId): ErrorOrResponse[PersonalInvitationToken] = {
    val request = Request.Get("/i/users/invitation-code", headers = basicAuthHeader, timeout = AsyncClient.DefaultTimeout)
    client(URI.parse(Request.query(s"${backend.baseUrl}/i/users/invitation-code", "inviter" -> inviter.str, "invitation_id" -> invitation.str)), request) map {
      case Response(SuccessHttpStatus(), CodeExtractor(code), headers) => Right(PersonalInvitationToken(code))
      case Response(_, ErrorResponse(status, message, label), _) => Left(ErrorResponse(status, message, label))
      case other => Left(ErrorResponse(other.status.status, other.toString, "unknown"))
    }
  }

  private def basicAuthHeader: String Map String = {
    val auth = user + ":" + password
    val encoded = Base64.encodeToString(auth.getBytes("UTF-8"), Base64.DEFAULT)
    Map("Authorization" -> s"Basic $encoded")
  }

  private def verifyRequestBody(email: EmailAddress, code: ConfirmationCode) = JsonContentEncoder(JsonEncoder { o =>
    o.put("email", email.str)
    o.put("code", code.str)
  })

  private case class Activation(key: String, code: String)

  private object CodeExtractor {
    def unapply(resp: ResponseContent): Option[String] = resp match {
      case JsonObjectResponse(js) => Try { js.getString("code") } .toOption
      case _ => None
    }
  }
}
