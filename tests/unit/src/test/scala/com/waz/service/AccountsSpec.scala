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

import com.waz.api.ClientRegistrationState
import com.waz.api.impl.{Credentials, EmailCredentials, ErrorResponse, PhoneCredentials}
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.sync.client.UsersClient
import com.waz.testutils._
import com.waz.testutils.Matchers._
import com.waz.threading.CancellableFuture
import com.waz.znet.AuthenticationManager.{Cookie, Token}
import com.waz.znet.LoginClient.LoginResult
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet.{LoginClient, ZNetClient}
import org.robolectric.shadows.ShadowLog
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers, RobolectricTests}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class AccountsSpec extends FeatureSpec with Matchers with BeforeAndAfter with RobolectricTests with ScalaFutures with DefaultPatienceConfig {

  var loginRequest: Option[Credentials] = _
  var loginResponse: LoginResult = _
  var loadSelfResponse: Either[ErrorResponse, UserInfo] = _

  lazy val global = new MockGlobalModule {
    override lazy val loginClient: LoginClient = new LoginClient(client, BackendConfig.StagingBackend) {
      override def login(userId: AccountId, credentials: Credentials) = {
        loginRequest = Some(credentials)
        CancellableFuture successful loginResponse
      }
    }
    override lazy val factory: MockZMessagingFactory = new MockZMessagingFactory(this) {
      override def usersClient(client: ZNetClient): UsersClient = new UsersClient(client) {
        override def loadSelf(): ErrorOrResponse[UserInfo] = CancellableFuture successful loadSelfResponse
      }

      override def userModule(userId: UserId, account: AccountService): UserModule =
        new UserModule(userId, account) {
          override def ensureClientRegistered(account: AccountData): Future[Either[ErrorResponse, AccountData]] = {
            Future successful Right(account.copy(clientId = account.clientId.orElse(Some(ClientId())), clientRegState = ClientRegistrationState.REGISTERED))
          }
        }
    }
  }
  lazy val accounts = new MockAccounts(global)

  before {
    ShadowLog.stream = null
    loginRequest = None
    loginResponse = Left((Some("123"), ErrorResponse(0, "", "")))
    loadSelfResponse = Left(ErrorResponse(0, "", ""))
  }

  feature("login") {

    scenario("login with unverified user") {
      loginResponse = Left((Some("123"), ErrorResponse(403, "message", "pending-activation")))

      val creds = EmailCredentials(EmailAddress("email"), Some("pass"))
      Await.result(accounts.login(creds), 2.seconds) match {
        case Right(data) =>
          data.email shouldEqual Some(EmailAddress("email"))
          data.credentials shouldEqual creds
          data.verified shouldEqual false
          data.userId shouldBe empty
        case res =>
          fail(s"login returned: $res")
      }
    }
  }

  feature("re-login using different credentials") {

    lazy val phone = PhoneNumber("+12345678901")
    lazy val email = EmailAddress("test@email.com")
    lazy val userInfo = UserInfo(UserId(), Some("user name"), Some(1), Some(email), Some(phone))

    var accountId = ""

    scenario("log in with phone number") {
      val accountsSize = accounts.accountMap.size

      loginResponse = Right((Token("token", "Bearer", System.currentTimeMillis() + 15.minutes.toMillis), Some(Cookie("cookie"))))
      loadSelfResponse = Right(userInfo)

      val Right(data) = accounts.login(PhoneCredentials(phone, Some(ConfirmationCode("code")))).await()
      data.phone shouldEqual Some(phone)
      data.verified shouldEqual true
      data.email shouldEqual Some(email)
      accountId = data.id.str
      accounts.currentAccountPref().await() shouldEqual data.id.str
      accounts.accountMap should have size (accountsSize + 1)
    }

    scenario("log out") {
      accounts.logout().await()
    }

    scenario("log in with email") {
      val accountsSize = accounts.accountMap.size
      loginResponse = Right((Token("token", "Bearer", System.currentTimeMillis() + 15.minutes.toMillis), Some(Cookie("cookie"))))
      loadSelfResponse = Right(userInfo)

      val Right(data) = accounts.login(EmailCredentials(email, Some("passwd"))).await()
      data.id.str shouldEqual accountId
      data.phone shouldEqual Some(phone)
      data.email shouldEqual Some(email)
      data.verified shouldEqual true
      accounts.accountMap should have size accountsSize
      accounts.currentAccountPref().await() shouldEqual data.id.str
    }

    scenario("log out again") {
      accounts.logout().await()
    }

    scenario("log in with new password (after changing it on backend)") {
      val accountsSize = accounts.accountMap.size
      loginResponse = Right((Token("token", "Bearer", System.currentTimeMillis() + 15.minutes.toMillis), Some(Cookie("cookie"))))
      loadSelfResponse = Right(userInfo)

      val Right(data) = accounts.login(EmailCredentials(email, Some("new_password"))).await()
      data.id.str shouldEqual accountId
      data.phone shouldEqual Some(phone)
      data.email shouldEqual Some(email)
      data.verified shouldEqual true
      accounts.accountMap should have size accountsSize
      accounts.currentAccountPref().await() shouldEqual data.id.str
    }
  }
}
