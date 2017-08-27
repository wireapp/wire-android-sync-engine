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
package com.waz.api.impl

import android.net.Uri
import com.koushikdutta.async.http.WebSocket
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.ZMessagingApi.RegistrationListener
import com.waz.api.{ClientRegistrationState, CredentialsFactory, InitListener, LoginListener}
import com.waz.client.{RegistrationClient, RegistrationClientImpl}
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service._
import com.waz.service.push.WebSocketClientService
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import com.waz.testutils.{DefaultPatienceConfig, EmptySyncService, MockAccountsService, MockGlobalModule, MockUiModule, MockZMessagingFactory}
import com.waz.threading.CancellableFuture
import com.waz.ui.UiModule
import com.waz.utils.events.EventContext
import com.waz.utils.{IoUtils, Json}
import com.waz.znet.AuthenticationManager.{Cookie, Token}
import com.waz.znet.ContentEncoder.{BinaryRequestContent, EmptyRequestContent, RequestContent}
import com.waz.znet.Response.HttpStatus
import com.waz.znet._
import com.waz.{RobolectricUtils, service}
import org.json.JSONObject
import org.robolectric.shadows.ShadowLog
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import com.waz.ZLog.ImplicitTag._
import com.waz.content.GlobalPreferences.CurrentAccountPref
import com.waz.utils.wrappers.URI
import com.waz.znet.LoginClient.LoginResult

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NoStackTrace

@Ignore class RegistrationSpec extends FeatureSpec with Matchers with OptionValues with BeforeAndAfter with RobolectricTests with RobolectricUtils with ScalaFutures with DefaultPatienceConfig { test =>
  import com.waz.threading.Threading.Implicits.Background

  implicit val ec = EventContext.Global
  val timeout = 5.seconds

  lazy val selfUser = UserData("Self User")
  lazy val otherUser = UserData("Other User")

  var loginResponse: LoginResult = _
  var registerResponse: Either[ErrorResponse, (UserInfo, Option[Cookie])] = _
  var response: ((URI, RequestContent)) => Response = _
  var request: Option[(URI, RequestContent)] = _
  var selfUserSyncRequested = false

  class MockGlobal extends MockGlobalModule {

    override lazy val client: AsyncClientImpl = new AsyncClientImpl(wrapper = TestClientWrapper()) {
      override def apply(req: Request[_]): CancellableFuture[Response] = {
        val body = req.getBody
        val uri = req.absoluteUri.get
        println(s"uri: $uri, body: $body")
        request = Some((uri, body))
        CancellableFuture.successful(response(request.value))
      }
    }

    override lazy val loginClient: LoginClient = new LoginClientImpl(client, backend) {
      override def login(account: AccountData) = CancellableFuture.successful(loginResponse)
      override def access(cookie: Cookie, token: Option[Token]) = CancellableFuture.successful(loginResponse)
    }
    override lazy val regClient: RegistrationClient = new RegistrationClientImpl(client, backend) {
      override def register(account: AccountData, name: String, accentId: Option[Int]) = CancellableFuture.successful(registerResponse)
    }
    override lazy val factory = new MockZMessagingFactory(this) {
      override def zmessaging(teamId: Option[TeamId], clientId: ClientId, userModule: UserModule): service.ZMessaging =
        new service.ZMessaging(teamId, clientId, userModule) {
          override lazy val sync = new EmptySyncService {
            override def syncSelfUser(): Future[SyncId] = {
              selfUserSyncRequested = true
              super.syncSelfUser()
            }
          }
          override lazy val websocket = new WebSocketClientService(context, lifecycle, zNetClient, auth, network, backend, clientId, timeouts, pushToken) {
            override private[waz] def createWebSocketClient(clientId: ClientId): WebSocketClient = new WebSocketClient(context, zNetClient.client.asInstanceOf[AsyncClientImpl], Uri.parse("/"), auth) {
              override protected def connect(): CancellableFuture[WebSocket] = CancellableFuture.failed(new Exception("mock") with NoStackTrace)
            }
          }
        }
    }
  }

  lazy val global = new MockGlobal
  lazy val instance = new MockAccountsService(global)

  implicit lazy val ui: UiModule = MockUiModule(instance)

  lazy val api = new ZMessagingApi()(ui)


  before {
    loginResponse = Left((Some("123"), ErrorResponse(0, "", "")))
    registerResponse = Left(ErrorResponse(0, "", ""))
    response = { _ => Response(Response.Cancelled) }
    request = None
    selfUserSyncRequested = true

    api.onCreate(context)
    api.onResume()

    var initialized = false
    api.onInit(new InitListener {
      override def onInitialized(s: com.waz.api.Self): Unit = {
        initialized = true
      }
    })

    withDelay(initialized shouldEqual true)
  }

  after {
    ShadowLog.stream = null
//    if (api.account.exists(_.global.lifecycle.isUiActive)) {
//      api.onPause()
//      api.account foreach { acc =>
//        Thread.sleep(1000)
//        Await.result(acc.getZMessaging, 5.seconds) foreach { zms =>
//          val dbName = zms.db.dbHelper.getDatabaseName
//          Await.result(zms.db.close().flatMap(_ => zms.global.storage.close()), 5.seconds)
//          context.getDatabasePath(dbName).getParentFile.listFiles.foreach(_.delete())
//        }
//      }
//      api.onDestroy()
//    }

    (ui.accounts.activeAccountPref := None).futureValue
  }

  def zmessagingCreated(api: ZMessagingApi = test.api) = api.account.flatMap(_.zmessaging.currentValue).flatten.isDefined

  feature("New user registration") {

    object LoginUri {
      def unapply(uri: URI): Boolean = uri.getPath.startsWith("/login")
    }
    object SelfUri {
      def unapply(uri: URI): Boolean = uri.getPath.startsWith("/self")
    }
    object ClientsUri {
      def unapply(uri: URI): Boolean = uri.getPath.startsWith("/clients")
    }

    scenario("Register new user, verify email right away, and set picture") {
      val selfUserId = UserId()
      registerResponse = Right((UserInfo(selfUserId, Some("name"), Some(0), Some(EmailAddress("email"))), Some(Cookie("sd-zuid=asd;asd"))))
      loginResponse = Left((Some("123"), ErrorResponse(403, "", "pending-activation")))
      response = { _ => Response(HttpStatus(403), JsonObjectResponse(Json("code" -> 403, "message" -> "invalid credentials", "label" -> "pending-activation"))) }

      var self: com.waz.api.Self = null
      api.register(CredentialsFactory.emailCredentials("email", "passwd"), "name", AccentColor(0), new RegistrationListener {
        override def onRegistrationFailed(i: Int, s: String, s1: String): Unit = {
          println(s"registration failed $i, $s, $s1")
        }
        override def onRegistered(s: com.waz.api.Self): Unit = self = s
      })

      withDelay {
        self should not be null
        self.isLoggedIn shouldEqual true
        self.getUser.getId shouldEqual selfUserId.str
        self.accountActivated shouldEqual false
        zmessagingCreated() shouldEqual false
      }
      val clientId = ClientId()

      response = {
        case (SelfUri(), EmptyRequestContent) =>
          Response(HttpStatus(200), JsonObjectResponse(Json("id" -> selfUserId.str, "name" -> "name", "email" -> "email")))
        case (ClientsUri(), _) =>
          Response(HttpStatus(200), JsonObjectResponse(Json("id" -> clientId.str))) // TODO: return clients as expected
        case _ =>
          Response(HttpStatus(400), EmptyResponse)
      }
      loginResponse = Right((Token("", "Bearer", System.currentTimeMillis() + 10.minutes.toMillis), Some(Cookie("sd-zuid=asd;asd"))))


      within (10.seconds) {
        self.isLoggedIn shouldEqual true
        self.accountActivated shouldEqual true
        self.getEmail shouldEqual "email"
        Option(self.getUser) shouldBe defined
        Option(self.getUser).map(_.data).filter(_ != UserData.Empty) shouldBe defined
        self.getClientRegistrationState shouldEqual ClientRegistrationState.REGISTERED
        zmessagingCreated() shouldEqual true
      }


      self.setPicture(ui.images.createImageAssetFrom(IoUtils.toByteArray(getClass.getResourceAsStream("/images/penguin.png"))))

      lazy val imageAsset = self.getPicture
      withDelay {
        Option(self.getUser).flatMap(_.data.picture) shouldBe defined
        self.getPicture should not be empty
        imageAsset.data should not be AssetData.Empty
      }

      val asset = api.zmessaging.flatMap(_.get.assetsStorage.get(imageAsset.data.id)).await()
      asset should be('defined)
      asset.flatMap(_.convId).map(_.str) shouldEqual Some(selfUserId.str)

      idle(500.millis)
      self.isLoggedIn shouldEqual true
      selfUserSyncRequested shouldEqual true
    }

    scenario("Register new user, restart the app, verify email, login again") {
      val selfUserId = UserId()
      registerResponse = Right((UserInfo(selfUserId, Some("name"), Some(0), Some(EmailAddress("email1"))), Some(Cookie("sd-zuid=asd;asd"))))
      loginResponse = Left((Some("123"), ErrorResponse(403, "", "pending-activation")))
      response = _ => Response(HttpStatus(403), JsonObjectResponse(Json("code" -> 403, "message" -> "invalid credentials", "label" -> "")))

      var self: com.waz.api.Self = null
      var res = ""
      api.register(CredentialsFactory.emailCredentials("email1", "passwd"), "name", AccentColor(0), new RegistrationListener {
        override def onRegistrationFailed(i: Int, s: String, s1: String): Unit = { res = s }
        override def onRegistered(s: com.waz.api.Self): Unit = {
          self = s
          res = "done"
        }
      })

      withDelay {
        res shouldEqual "done"
        self should not be null
        self.isLoggedIn shouldEqual true
        self.getUser.getId shouldEqual selfUserId.str
        self.getEmail shouldEqual "email1"
        self.accountActivated shouldEqual false
        zmessagingCreated() shouldEqual false
      }

      withDelay(global.prefs.getFromPref(CurrentAccountPref) should not be None)

      val accountId = api.ui.accounts.activeAccountPref().futureValue.get

      self = null

      idle(1.second) // wait for storage to sync


      api.onPause()
      idle(1.second)
      ui.accounts.storage.update(accountId, _.copy(password = None)).futureValue
      api.account.foreach { acc =>
        debug(s"closing db: ${acc.storage.db.dbHelper.getDatabaseName}")
        acc.storage.db.close().await()
      }
      ui.accounts.ec.onContextStop()
      ui.accounts.ec.onContextDestroy()
      ui.accounts.accountMap.clear()
      idle(1.second)

      val clientId = ClientId()

      request = None
      response = {
        case (SelfUri(), EmptyRequestContent) =>
          Response(HttpStatus(200), JsonObjectResponse(Json("id" -> selfUserId.str, "name" -> "name", "email" -> "email1")))
        case (ClientsUri(), _) =>
          Response(HttpStatus(200), JsonObjectResponse(Json("id" -> clientId.str)))
        case req @ (uri, BinaryRequestContent(content, "application/json")) if uri.getLastPathSegment == "login" && new JSONObject(new String(content)).getString("password") == "passwd" =>
          Response(HttpStatus(200), JsonObjectResponse(Json("access_token" -> "token", "expires_in" -> 36000, "token_type" -> "Bearer")), Response.createHeaders(LoginClient.SetCookie -> "sd-zuid=asd;asd"))
        case req =>
          Response(HttpStatus(403), JsonObjectResponse(Json("code" -> 403, "message" -> "invalid credentials", "label" -> "")))
      }

      debug("##### starting new api")

      val api2 = new ZMessagingApi()(MockUiModule(new MockAccountsService(global)))
      api2.onCreate(context)
      api2.onResume()

      ui.accounts.activeAccountPref().futureValue should not be ""

      api2.onInit(new InitListener {
        override def onInitialized(s: com.waz.api.Self): Unit = {
          self = s
        }
      })

      withDelay {
        self should not be null
        self.isLoggedIn shouldEqual true
        self.getUser should not be null
        self.getEmail shouldEqual "email1"
        self.accountActivated shouldEqual false
        zmessagingCreated(api2) shouldEqual false
      }

      loginResponse = Right((Token("", "Bearer", System.currentTimeMillis() + 10.minutes.toMillis), Some(Cookie("sd-zuid=asd;asd"))))
      res = ""
      api2.login(CredentialsFactory.emailCredentials("email1", "passwd"), new LoginListener {
        override def onSuccess(user: com.waz.api.Self): Unit = res = "done"
        override def onFailed(code: Int, message: String, label: String): Unit = res = message
      })

      withDelay {
        res shouldEqual "done"
        self.getEmail shouldEqual "email1"
        self.accountActivated shouldEqual true
        self.isLoggedIn shouldEqual true
        self.getUser should not be null
        self.getUser.getId shouldEqual selfUserId.str
        zmessagingCreated(api2) shouldEqual true
      }

      api.onResume()
    }
  }
}
