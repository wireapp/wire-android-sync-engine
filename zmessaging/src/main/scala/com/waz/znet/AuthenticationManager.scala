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
package com.waz.znet

import com.koushikdutta.async.http.AsyncHttpRequest
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.content.AccountsStorageNew
import com.waz.model._
import com.waz.service.ZMessaging.accountTag
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.AuthenticationManager.Token
import com.waz.znet.LoginClient.LoginResult
import com.waz.znet.Response._
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.Future

trait AccessTokenProvider {
  def currentToken(): Future[Either[Status, Token]]
}

/**
 * Manages authentication token, and dispatches login requests when needed.
 * Will retry login request if unsuccessful.
 */
class AuthenticationManager(id: UserId, accStorage: AccountsStorageNew, client: LoginClient) extends AccessTokenProvider {

  lazy implicit val logTag: LogTag = accountTag[AuthenticationManager](id)

  import com.waz.znet.AuthenticationManager._

  implicit val dispatcher = new SerialDispatchQueue(name = "AuthenticationManager")

  private var closed = false

  private def token  = withAccount(_.accessToken)
  private def cookie = withAccount(_.cookie)

  private def account = withAccount(identity)

  private def withAccount[A](f: (AccountDataNew) => A): Future[A] = accStorage.get(id).map {
    case Some(acc) => f(acc)
    case _         => throw new IllegalStateException(s"Could not find matching account for: $id")
  }

  //Only performs safe update - never wipes either the cookie or the token.
  private def updateCredentials(token: Option[Token] = None, cookie: Option[Cookie] = None) = {
    def copy(acc: AccountDataNew): AccountDataNew =
      (token, cookie) match {
        case (Some(t), Some(c)) => acc.copy(accessToken = t, cookie = c)
        case (Some(t), None)    => acc.copy(accessToken = t)
        case (None, Some(c))    => acc.copy(cookie = c)
        case _                  => acc
      }
    accStorage.update(id, copy)
  }

  //TODO - proper logout, de-register push token, inform backend, etc.
  private def wipeCredentials() = accStorage.remove(id)

  /**
   * Last login request result. Used to make sure we never send several concurrent login requests.
   */
  private var loginFuture: CancellableFuture[Either[Status, Token]] = CancellableFuture.lift(token.map { Right(_) })

  def invalidateToken() = token.map(t => updateCredentials(Some(t.copy(expiresAt = 0))))

  def isExpired(token: Token) = token.expiresAt - ExpireThreshold < System.currentTimeMillis()

  def close() = dispatcher {
    closed = true
    loginFuture.cancel()
  }

  /**
   * Returns current token if not expired or logs the user out
   */
  def currentToken(): Future[Either[Status, Token]] = {
    loginFuture = loginFuture.recover {
      case ex =>
        warn(s"login failed", ex)
        Left(Cancelled)
    } flatMap { _ =>
      CancellableFuture.lift(token) flatMap {
        case token if !isExpired(token) =>
          verbose(s"Non expired token: $token")
          CancellableFuture.successful(Right(token))
        case token =>
          CancellableFuture.lift(cookie).flatMap { cookie =>
            debug(s"Non existent or expired token: $token, will attempt to refresh with cookie: $cookie")
            dispatchRequest(client.access(cookie, Some(token))) {
              case Left((requestId, resp @ ErrorResponse(Status.Forbidden | Status.Unauthorized, message, label))) =>
                verbose(s"access request failed (label: $label, message: $message), will try login request. token: $token, cookie: $cookie, access resp: $resp")

                HockeyApp.saveException(new RuntimeException(s"Access request: $requestId failed: msg: $message, label: $label, cookie expired at: ${cookie.expiry} (is valid: ${cookie.isValid}), token expired at: ${token.expiresAt} (is valid: ${token.isValid})"), null)

                CancellableFuture.lift(wipeCredentials()).map { _ =>
                  Left(HttpStatus(resp.code, resp.message))
                }
            }
          }
      }
    }
    loginFuture.future
  }

  private def dispatchRequest(request: => CancellableFuture[LoginResult], retryCount: Int = 0)(handler: ResponseHandler): CancellableFuture[Either[Status, Token]] =
    request flatMap handler.orElse {
      case Right((token, cookie)) =>
        debug(s"receivedAccessToken: '$token'")
        CancellableFuture.lift {
          updateCredentials(Some(token), cookie).map(_ => Right(token))
        }

      case Left(_) if closed => CancellableFuture.successful(Left(ClientClosed))

      case Left((_, err @ ErrorResponse(Cancelled.status, msg, label))) =>
        debug(s"request has been cancelled")
        CancellableFuture.successful(Left(HttpStatus(err.code, s"$msg - $label")))

      case Left(err) if retryCount < MaxRetryCount =>
        info(s"Received error from request: $err, will retry")
        dispatchRequest(request, retryCount + 1)(handler)

      case Left((_, err)) =>
        val msg = s"Login request failed after $retryCount retries, last status: $err"
        error(msg)
        CancellableFuture.successful(Left(HttpStatus(err.code, msg)))
    }
}

object AuthenticationManager {
  val MaxRetryCount = 3
  val ExpireThreshold = 15 * 1000 // refresh access token on background if it is close to expire

  type ResponseHandler = PartialFunction[LoginResult, CancellableFuture[Either[Status, Token]]]

  case class Cookie(str: String) {

    private val parts = str.split('.').toSet
    val headers = Map(LoginClient.Cookie -> s"zuid=$str")
    val expiry = find("d=").map(v => Instant.ofEpochSecond(v.toLong))
    def isValid = expiry.exists(_.isAfter(Instant.now))

    private def find(pref: String) = parts.find(_.contains(pref)).map(_.drop(2))

    override def toString = s"${str.take(10)}, exp: $expiry, isValid: $isValid"
  }

  case class Token(accessToken: String, tokenType: String, expiresAt: Long = 0) {
    val headers = Map(Token.AuthorizationHeader -> s"$tokenType $accessToken")
    def prepare(req: AsyncHttpRequest) = req.addHeader(Token.AuthorizationHeader, s"$tokenType $accessToken")

    def isValid = expiresAt > System.currentTimeMillis()

    override def toString = s"${accessToken.take(10)}, exp: $expiresAt, isValid: $isValid"
  }

  object Token extends ((String, String, Long) => Token ){
    val AuthorizationHeader = "Authorization"

    implicit lazy val Encoder: JsonEncoder[Token] = new JsonEncoder[Token] {
      override def apply(v: Token): JSONObject = JsonEncoder { o =>
        o.put("token", v.accessToken)
        o.put("type", v.tokenType)
        o.put("expires", v.expiresAt)
      }
    }

    implicit lazy val Decoder: JsonDecoder[Token] = new JsonDecoder[Token] {
      import JsonDecoder._
      override def apply(implicit js: JSONObject): Token = Token('token, 'type, 'expires)
    }
  }
}
