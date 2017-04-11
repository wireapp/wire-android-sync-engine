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
import com.waz.ZLog._
import com.waz.api.impl.{Credentials, EmailCredentials, ErrorResponse}
import com.waz.content.Preference
import com.waz.model.{AccountId, EmailAddress}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.AuthenticationManager.{Cookie, Token}
import com.waz.znet.LoginClient.LoginResult
import com.waz.znet.Response._
import net.hockeyapp.android.ExceptionHandler
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.Future

trait AccessTokenProvider {
  def currentToken(): Future[Either[Status, Token]]
}

trait CredentialsHandler {
  val userId: AccountId
  val cookie: Preference[Option[Cookie]]
  val accessToken: Preference[Option[Token]]

  def credentials: Credentials
  def onInvalidCredentials(): Unit = {}
}

class BasicCredentials(email: EmailAddress, password: Option[String]) extends CredentialsHandler {
  override val userId = AccountId()
  override val credentials = EmailCredentials(email, password)
  override val accessToken = Preference.inMemory(Option.empty[Token])
  override val cookie = Preference.inMemory(Option.empty[Cookie])
}

/**
 * Manages authentication token, and dispatches login requests when needed.
 * Will retry login request if unsuccessful.
 */
class AuthenticationManager(client: LoginClient, user: CredentialsHandler) extends AccessTokenProvider {
  def this(client: LoginClient, email: EmailAddress, passwd: String) = this(client, new BasicCredentials(email, Some(passwd))) // currently only used in integration tests

  import com.waz.znet.AuthenticationManager._

  debug(s"init, \ncredentials: ${user.credentials}")

  implicit val dispatcher = new SerialDispatchQueue(name = "AuthenticationManager")

  private var closed = false

  private val tokenPref = user.accessToken

  /**
   * Last login request result. Used to make sure we never send several concurrent login requests.
   */
  private var loginFuture: CancellableFuture[Either[Status, Token]] = CancellableFuture.lift(tokenPref() map { _.fold[Either[Status, Token]](Left(Cancelled))(Right(_)) })

  def invalidateToken() = tokenPref() .map (_.foreach { token => tokenPref := Some(token.copy(expiresAt = 0)) })(dispatcher)

  def isExpired(token: Token) = token.expiresAt - expireThreshold < System.currentTimeMillis()

  def close() = dispatcher {
    closed = true
    loginFuture.cancel()
  }

  /**
   * Returns current token if not expired or performs login request
   */
  def currentToken(): Future[Either[Status, Token]] = {
    loginFuture = loginFuture.recover {
      case ex =>
        warn(s"login failed", ex)
        Left(Cancelled)
    } flatMap { _ =>
      CancellableFuture.lift(tokenPref()) flatMap {
        case Some(token) if !isExpired(token) =>
          verbose(s"Non expired token: $token")
          CancellableFuture.successful(Right(token))
        case token =>
          CancellableFuture.lift(user.cookie()).flatMap { cookie =>
            debug(s"Non existent or expired token: $token, will attempt to refresh with cookie: $cookie")
            cookie match {
              case Some(c) =>
                dispatchRequest(client.access(c, token)) {
                  case Left((requestId, resp @ ErrorResponse(Status.Forbidden | Status.Unauthorized, message, label))) =>
                    verbose(s"access request failed (label: $label, message: $message), will try login request. token: $token, cookie: $cookie, access resp: $resp")

                    ExceptionHandler.saveException(new RuntimeException(s"Access request: $requestId failed: msg: $message, label: $label, cookie expired at: ${cookie.map(_.expiry)} (is valid: ${cookie.exists(_.isValid)}), token expired at: ${token.map(_.expiresAt)} (is valid: ${token.exists(_.isValid)})"), null)
                    for {
                      _ <- CancellableFuture.lift(user.cookie := None)
                      _ <- CancellableFuture.lift(user.accessToken := None)
                      res <- dispatchLoginRequest()
                    } yield res
                }
              case None =>
                dispatchLoginRequest()
            }
          }
      }
    }
    loginFuture.future
  }

  private def dispatchLoginRequest(): CancellableFuture[Either[Status, Token]] =
    if (user.credentials.canLogin) {
      dispatchRequest(client.login(user.userId, user.credentials)) {
        case Left((_, resp @ ErrorResponse(Status.Forbidden, _, _))) =>
          debug(s"login request failed with: $resp")
          user.onInvalidCredentials()
          CancellableFuture.successful(Left(HttpStatus(Status.Unauthorized, s"login request failed with: $resp")))
      }
    } else { // no cookie, no password/code, therefore unable to login, don't even try
      debug("Password or confirmation code missing in dispatchLoginRequest, returning Unauthorized")
      user.onInvalidCredentials()
      CancellableFuture.successful(Left(HttpStatus(Status.Unauthorized, "Password missing in dispatchLoginRequest")))
    }

  private def dispatchRequest(request: => CancellableFuture[LoginResult], retryCount: Int = 0)(handler: ResponseHandler): CancellableFuture[Either[Status, Token]] =
    request flatMap handler.orElse {
      case Right((token, cookie)) =>
        debug(s"receivedAccessToken: '$token'")

        CancellableFuture.lift(for {
          _ <- tokenPref := Some(token)
        //Only save the cookie if we actually get one back - we don't want to set it to None!!
          _ <- cookie match {
            case Some(c) => user.cookie := Some(c)
            case _ => Future.successful({})
          }
        } yield Right(token))

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
  private implicit val logTag: LogTag = logTagFor[AuthenticationManager]

  val MaxRetryCount = 3
  val expireThreshold = 15 * 1000 // refresh access token on background if it is close to expire

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
