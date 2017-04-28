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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.waz.RobolectricUtils
import com.waz.content.Preference
import com.waz.model.{AccountId, EmailAddress}
import com.waz.service.BackendConfig
import com.waz.testutils.Matchers._
import com.waz.testutils.Slow
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.znet.AuthenticationManager.Token
import com.waz.znet.Response.{HttpStatus, Status}
import org.robolectric.shadows.ShadowLog
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class AuthenticationManagerSpec extends FeatureSpecLike with Matchers with BeforeAndAfter with RobolectricTests with RobolectricUtils { test =>

  val wireMockPort = 9000 + Random.nextInt(3000)
  val wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().port(wireMockPort))
  implicit lazy val executionContext = Threading.Background

  @volatile private var currentAccessToken = Option.empty[Token]

  before {
    currentAccessToken = None
    client.lastRequestTime = 0L
    client.failedAttempts = 0
    wireMockServer.start()
    configureFor("localhost", wireMockPort)
    reset()
  }

  after {
    wireMockServer.stop()
    ShadowLog.stream = null
  }

  val email = "test@test.com"
  val password = "password"
  val userId = AccountId()
  val accessToken = "bb5373102ef17eb4d350d0bc84482a1357ab1a98a34b941f36c46a402b8fa62b.1.1394202093.a.333b8897-7950-42bd-964d-4f2dad285aef.10694619285061153311"
  val cookie = "23cf0ff8e4b469b481dddab57ba286a58ca1787ad877aa769ec4d97f48cbc90f.1.1394989660.u.b6f21a36-fc02-4b44-a1e6-608a7465246d"
  val cookieResponse = s"zuid=$cookie; path=/access; expires=Sun, 16-Mar-2014 17:07:40 GMT; domain=z-infra.com; Secure; HttpOnly"

  lazy val client = new LoginClient(new AsyncClient(), BackendConfig("http://localhost:" + wireMockPort))

  def manager(callback: () => Unit = {() => }) = new AuthenticationManager(client, new BasicCredentials(EmailAddress(email), Some(password)) {
    override val userId: AccountId = test.userId
    override def onInvalidCredentials(): Unit = callback()

    override val accessToken = new Preference[Option[Token]] {
      override protected implicit def dispatcher = null
      override def default: Option[Token] = None
      override def apply(): Future[Option[Token]] = Future.successful(currentAccessToken)
      override def update(value: Option[Token]) = Future.successful { currentAccessToken = value }
    }
  })

  def loginReqJson = s"""{"email":"$email","label":"$userId","password":"$password"}"""

  def verifyLoginRequested() =
    verify(postRequestedFor(urlEqualTo("/login?persist=true"))
      .withRequestBody(equalToJson(loginReqJson))
      .withHeader("Content-Type", equalTo("application/json")))

  def verifyAccessRequested(token: String, cookie: String) =
    verify(postRequestedFor(urlEqualTo("/access"))
      .withHeader("Authorization", equalTo(s"Bearer $token"))
      .withHeader("Cookie", equalTo(s"zuid=$cookie")))

  def stubLogin(token: String = accessToken, expiresIn: Int = 3600, status: Int = 200, delay: FiniteDuration = 0.millis) =
    stubFor(post(urlEqualTo("/login?persist=true"))
      .withHeader("Content-Type", equalTo("application/json"))
      .withRequestBody(equalToJson(loginReqJson))
      .willReturn(aResponse()
      .withStatus(status)
      .withHeader("Content-Type", "application/json")
      .withHeader("Set-Cookie", cookieResponse)
      .withBody(s"""{"expires_in":$expiresIn,"access_token":"$token","token_type":"Bearer"}""")
      .withFixedDelay(delay.toMillis.toInt)
      ))

  def stubLoginForbidden(login: String = email, passwd: String = password) =
    stubFor(post(urlEqualTo("/login?persist=true"))
      .withHeader("Content-Type", equalTo("application/json"))
      .withRequestBody(equalToJson(loginReqJson))
      .willReturn(aResponse().withStatus(Status.Forbidden)))

  def stubAccess(currentToken: String = accessToken, newToken: String = accessToken, currentCookie: String = cookie) =
    stubFor(post(urlEqualTo("/access"))
      .withHeader("Authorization", equalTo(s"Bearer $currentToken"))
      .withHeader("Cookie", containing(s"zuid=$currentCookie"))
      .willReturn(aResponse()
      .withStatus(200)
      .withHeader("Content-Type", "application/json")
      .withBody(s"""{"expires_in":3600,"access_token":"$newToken","token_type":"Bearer"}""")
      ))

  def stubAccessExpired(currentToken: String = accessToken, currentCookie: String = cookie) =
  stubFor(post(urlEqualTo("/access"))
    .withHeader("Authorization", equalTo(s"Bearer $currentToken"))
    .withHeader("Cookie", containing(s"zuid=$currentCookie"))
    .willReturn(aResponse()
      .withStatus(Status.Forbidden)
      .withHeader("Content-Type", "application/json")
      .withBody(s"""{}""")
      ))

  feature("Logging in") {

    def verify[A](token: Future[A], expected: String) = {
      Await.result(token, 5.seconds) match {
        case Right(Token(t, "Bearer", _)) => t shouldEqual expected
        case res => fail(s"got: $res")
      }
    }

    scenario("Perform successful login") {
      stubLogin()

      verify(manager().currentToken(), accessToken)
      verifyLoginRequested()
    }

    scenario("Perform successful login after 2 retries", Slow) {
      val future = manager().currentToken()
      withDelay { verifyLoginRequested() }(250.millis)
      reset()

      withDelay { verifyLoginRequested() }(2.seconds)
      reset()

      // finally will accept login request
      stubLogin()

      withDelay { verify(future, accessToken) } (4.seconds)
      verifyLoginRequested()
    }

    scenario("Login fails after 3 retries when getting 404s", Slow) {
      val future = manager().currentToken()
      withDelay { verifyLoginRequested() }(250.millis)
      reset()
      withDelay { verifyLoginRequested() }(2.seconds)
      reset()
      withDelay { verifyLoginRequested() }(4.seconds)

      Await.result(future, 8.seconds) match {
        case Left(HttpStatus(404, _)) => //expected
        case resp => fail(s"Received: '$resp' when HttpStatus(404) was expected")
      }
    }

    scenario("Receive invalid credentials, execute callback and return error immediately") {
      stubLoginForbidden()

      @volatile var invalidCredentials = false
      val auth = manager { () =>
        invalidCredentials = true
      }

      Await.result(auth.currentToken(), 200.millis) match {
        case Left(HttpStatus(Status.Unauthorized, _)) => //expected
        case resp => fail(s"Received: '$resp' when HttpStatus(401) was expected")
      }

      invalidCredentials shouldEqual true
    }

    scenario("Calling currentToken once already logged should not cause additional login requests") {
      val auth = manager()
      stubLogin()
      verify(auth.currentToken(), accessToken)

      verifyLoginRequested()
      reset()

      verify(auth.currentToken(), accessToken)

      Thread.sleep(5000)
      findAll(postRequestedFor(urlEqualTo("/login?persist=true"))).size() should be(0) // no login requests should be fired
    }

    scenario("Calling currentToken multiple times (concurrently) should use only one login request") {
      val auth = manager()
      stubLogin(delay = 500.millis)
      val futures =
        (0 to 10).map(i => auth.currentToken()) ++
        (0 to 10).map(i => CancellableFuture.delay((i * 100).millis).future.flatMap(_ => auth.currentToken()) )

      futures.foreach(verify(_, accessToken))

      findAll(postRequestedFor(urlEqualTo("/login?persist=true"))).size() should be(1) // only one login request should be fired

      Await.result(Future.sequence(futures), 5.seconds)
    }

    scenario("Receive server error", Slow) {
      stubLogin(status = 500)

      Await.result(manager().currentToken(), 25.seconds) match {
        case Left(HttpStatus(500, _)) => //expected
        case resp => fail(s"Received: '$resp' when HttpStatus(500) was expected")
      }
    }

    scenario("Don't return expired token") {
      val expiringToken = "1231231231232123.expiring"
      val auth = manager()

      stubLogin(token = expiringToken, expiresIn = 0)
      verify(auth.currentToken(), expiringToken)
      verifyLoginRequested()
      reset()

      Thread.sleep(250)
      stubAccess(expiringToken)
      verify(auth.currentToken(), accessToken)
      verifyAccessRequested(expiringToken, cookie)
    }

    scenario("Refresh token if it's close to expire") {
      val expiringToken = "1231231231232123.expiring"
      val auth = manager()

      // receive first token - will be close to expire
      stubLogin(token = expiringToken, expiresIn = 10)
      verify(auth.currentToken(), expiringToken)
      verifyLoginRequested()
      reset()

      // asking for token should trigger background access request
      stubAccess(expiringToken, accessToken)
      verify(auth.currentToken(), expiringToken)

      // wait a little and check if access was actually requested
      withDelay {
        verifyAccessRequested(expiringToken, cookie)
      } (1.second)
      reset()

      // there should be no login call now
      verify(auth.currentToken(), accessToken)
    }

    scenario("Token expired when refreshing") {
      val expiringToken = "1231231231232123.expiring"
      val auth = manager()

      // receive first token - will be close to expire
      stubLogin(token = expiringToken, expiresIn = 10)
      verify(auth.currentToken(), expiringToken)
      verifyLoginRequested()
      reset()

      // asking for token should trigger background access request
      stubAccessExpired(expiringToken)
      verify(auth.currentToken(), expiringToken)

      // wait a little and check if access was actually requested
      withDelay {
        verifyAccessRequested(expiringToken, cookie)
      } (1.second)
      reset()

      withDelay { currentAccessToken shouldEqual None }

      stubLogin()

      // there should be a login call now (since the token should be invalid)
      verify(auth.currentToken(), accessToken)
      verifyLoginRequested()
    }

    scenario("Try to relogin when refreshing expired token with cookie doesn't work") {
      val expiringToken = "1231231231232123.expiring"
      stubLogin(token = expiringToken, expiresIn = 0)

      val auth = manager()
      verify(auth.currentToken(), expiringToken)
      verifyLoginRequested()
      reset()

      Thread.sleep(100)
      stubAccessExpired(expiringToken)
      stubLogin()

      verify(auth.currentToken(), accessToken)
      verifyAccessRequested(expiringToken, cookie)
      verifyLoginRequested()
    }

    scenario("Force refresh token when client calls invalidate") {
      val token = "1231231231232123.token"
      stubLogin(token = token)

      val auth = manager()
      verify(auth.currentToken(), token)
      verifyLoginRequested()
      reset()

      auth.invalidateToken()
      awaitUi(100.millis)
      withDelay { currentAccessToken.fold(false)(auth.isExpired) shouldEqual true }

      stubAccess(token)

      verify(auth.currentToken(), accessToken)
      verifyAccessRequested(token, cookie)
    }

    scenario("Call invalid credentials callback if token refresh doesn't work") {
      stubLogin()

      @volatile var invalidCredentials = false
      val auth = manager { () =>
        invalidCredentials = true
      }
      verify(auth.currentToken(), accessToken)
      verifyLoginRequested()
      reset()

      auth.invalidateToken()
      awaitUi(100.millis)
      withDelay { currentAccessToken.fold(false)(auth.isExpired) shouldEqual true }

      stubAccessExpired(accessToken)
      stubLoginForbidden()

      auth.currentToken() should eventually(beMatching({
        case Left(HttpStatus(Status.Unauthorized, _)) => true
      }))
      verifyAccessRequested(accessToken, cookie)
      verifyLoginRequested()
      invalidCredentials shouldEqual true
    }
  }

  feature("Cancelling") {

  }
}
