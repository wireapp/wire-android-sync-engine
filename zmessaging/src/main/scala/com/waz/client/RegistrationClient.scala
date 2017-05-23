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
package com.waz.client

import com.waz.ZLog._
import com.waz.api.impl.{Credentials, ErrorResponse, PhoneCredentials}
import com.waz.api.{KindOfAccess, KindOfVerification}
import com.waz.model._
import com.waz.service.BackendConfig
import com.waz.sync.client.UsersClient.UserResponseExtractor
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.JsonEncoder
import com.waz.utils.Locales._
import com.waz.utils.wrappers.URI
import com.waz.znet.AuthenticationManager.Cookie
import com.waz.znet.ContentEncoder.JsonContentEncoder
import com.waz.znet.Response.Status._
import com.waz.znet.Response.{HttpStatus, Status, SuccessHttpStatus}
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet._
import org.json.JSONObject

import scala.concurrent.duration._

class RegistrationClient(client: AsyncClient, backend: BackendConfig) {
  import Threading.Implicits.Background
  import com.waz.client.RegistrationClient._
  private implicit val tag: LogTag = logTagFor[RegistrationClient]

  def register(userId: AccountId, credentials: Credentials, name: String, accentId: Option[Int]): ErrorOrResponse[(UserInfo, Option[Cookie])] = {
    val json = JsonEncoder { o =>
      o.put("name", name)
      o.put("label", userId.str)
      accentId foreach (o.put("accent_id", _))
      credentials.addToRegistrationJson(o)
      o.put("locale", bcp47.languageTagOf(currentLocale))
    }

    val request = Request.Post(RegisterPath, JsonContentEncoder(json), baseUri = Some(URI.parse(backend.baseUrl)), timeout = timeout)
    client(request) map {
      case resp @ Response(SuccessHttpStatus(), UserResponseExtractor(user), headers) =>
        debug(s"registration succeeded: $resp")
        Right((user, if (credentials.autoLoginOnRegistration) LoginClient.getCookieFromHeaders(headers) else None))
      case Response(_, ErrorResponse(code, msg, label), headers) =>
        info(s"register failed with error: ($code, $msg, $label), headers: $headers")
        Left(ErrorResponse(code, msg, label))
      case resp =>
        error(s"Unexpected response from register: $resp")
        Left(ErrorResponse(resp.status.status, resp.toString, "unknown"))
    }
  }

  def requestPhoneConfirmationCode(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] =
    postActivateSend(kindOfAccess) {
      JsonEncoder { o =>
        o.put("phone", phone.str)
        if (kindOfAccess == KindOfAccess.LOGIN_IF_NO_PASSWD) o.put("force", false)
        if (kindOfAccess == KindOfAccess.REGISTRATION) o.put("locale", bcp47.languageTagOf(currentLocale))
      }
    }

  def requestPhoneConfirmationCall(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] =
    postActivateSend(kindOfAccess) {
      JsonEncoder { o =>
        o.put("phone", phone.str)
        o.put("voice_call", true)
        if (kindOfAccess == KindOfAccess.LOGIN_IF_NO_PASSWD) o.put("force", false)
        if (kindOfAccess == KindOfAccess.REGISTRATION) o.put("locale", bcp47.languageTagOf(currentLocale))
      }
    }

  private def postActivateSend(kindOfAccess: KindOfAccess)(params: JSONObject): CancellableFuture[ActivateResult] = {
    val uri = kindOfAccess match {
      case KindOfAccess.LOGIN_IF_NO_PASSWD | KindOfAccess.LOGIN => LoginSendPath
      case KindOfAccess.REGISTRATION => ActivateSendPath
    }

    val request = Request.Post(uri, JsonContentEncoder(params), baseUri = Some(URI.parse(backend.baseUrl)), timeout = timeout)
    client(request) map {
      case resp @ Response(SuccessHttpStatus(), _, _) =>
        debug(s"confirmation code requested: $resp")
        ActivateResult.Success
      case Response(_, ErrorResponse(Status.Forbidden, _, "password-exists"), headers) =>
        ActivateResult.PasswordExists
      case Response(_, ErrorResponse(code, msg, label), headers) =>
        warn(s"requestPhoneNumberConfirmation($kindOfAccess) failed with error: ($code, $msg, $label), headers: $headers")
        ActivateResult.Failure(ErrorResponse(code, msg, label))
      case other =>
        error(s"Unexpected response from requestPhoneNumberConfirmation($kindOfAccess): $other")
        ActivateResult.Failure(ErrorResponse(other.status.status, other.toString, "unknown"))
    }
  }

  def verifyPhoneNumber(credentials: PhoneCredentials, kindOfVerification: KindOfVerification): ErrorOrResponse[Unit] = {
    val request = Request.Post(ActivatePath, activateRequestBody(credentials, kindOfVerification), baseUri = Some(URI.parse(backend.baseUrl)), timeout = timeout)
    client(request) map {
      case resp @ Response(SuccessHttpStatus(), _, _) =>
        debug(s"phone number verified: $resp")
        Right(())
      case Response(_, ErrorResponse(code, msg, label), headers) =>
        warn(s"verifyPhoneNumber failed with error: ($code, $msg, $label), headers: $headers")
        Left(ErrorResponse(code, msg, label))
      case other =>
        error(s"Unexpected response from verifyPhoneNumber: $other")
        Left(ErrorResponse(other.status.status, other.toString, "unknown"))
    }
  }
  import com.waz.sync.client.InvitationClient._

  def getInvitationDetails(token: PersonalInvitationToken): ErrorOrResponse[ConfirmedInvitation] = {
    val uri = infoPath(token)
    val request = Request.Get(uri.getPath, baseUri = Some(URI.parse(backend.baseUrl)))
    client(request) map {
      case Response(HttpStatus(Success, _), ConfirmedInvitation(inv), _) =>
        debug(s"received invitation details for $token: $inv")
        Right(inv)
      case Response(HttpStatus(BadRequest, "invalid-invitation-code"), EmptyResponse, headers) =>
        warn(s"invitation token not found: $token")
        Left(ErrorResponse(NotFound, "no such invitation code", "invalid-invitation-code"))
      case other =>
        ZNetClient.errorHandling("getInvitationInfo")(tag)(other)
    }
  }

  private def infoPath(token: PersonalInvitationToken): URI = URI.parse(InvitationPath).buildUpon.appendPath("info").appendQueryParameter("code", token.code).build
}

object RegistrationClient {
  val RegisterPath = "/register"
  val ActivatePath = "/activate"
  val ActivateSendPath = "/activate/send"
  val LoginSendPath = "/login/send"

  val timeout = 15.seconds

  sealed trait ActivateResult
  object ActivateResult {
    case object Success extends ActivateResult
    case object PasswordExists extends ActivateResult
    case class Failure(err: ErrorResponse) extends ActivateResult
  }


  def activateRequestBody(credentials: PhoneCredentials, kindOfVerification: KindOfVerification) = JsonContentEncoder(JsonEncoder { o =>
    o.put("phone", credentials.phone.str)
    credentials.code foreach { code => o.put("code", code.str) }
    o.put("dryrun", kindOfVerification == KindOfVerification.PREVERIFY_ON_REGISTRATION)
  })
}
