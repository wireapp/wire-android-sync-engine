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

import java.io.File
import java.util.concurrent.{CountDownLatch, TimeUnit}

import android.net.Uri
import com.koushikdutta.async.http.AsyncHttpClient.WebSocketConnectCallback
import com.koushikdutta.async.http.{AsyncHttpGet, WebSocket}
import com.waz.model.EmailAddress
import com.waz.provision.ProvisionedSuite
import com.waz.service.{BackendConfig, GlobalModule}
import com.waz.utils.Json
import com.waz.utils.events.EventContext
import com.waz.znet.ClientWrapper.toKoushi
import com.waz.znet.Request._
import com.waz.znet.Response.SuccessHttpStatus
import com.waz.{RobolectricUtils, ShadowLogging}
import org.robolectric.Robolectric
import org.scalatest._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class WebSocketClientSpec extends FeatureSpec with Matchers with ProvisionedSuite with ShadowLogging with RobolectricTests with RobolectricUtils {
  override val provisionFile = "/one_user.json"

  override protected lazy val logfileBaseDir: File = new File("target/logcat/integration")

  val backend = BackendConfig.StagingBackend

  lazy val globalModule: GlobalModule = new GlobalModule(Robolectric.application, backend) {
    override lazy val clientWrapper: Future[ClientWrapper] = TestClientWrapper()
  }
  lazy val asyncClient = globalModule.client
  lazy val loginClient = new LoginClient(asyncClient, backend)
  lazy val auth = new AuthenticationManager(loginClient, EmailAddress(provisionedEmail("auto1")), "auto1_pass")

  feature("Websocket connection") {

    scenario("Connect with basic client") {
      val token = Await.result(auth.currentToken(), 5.seconds)
      var webSocket = None : Option[WebSocket]

      val req = token.right.get.prepare(new AsyncHttpGet(backend.pushUrl))
      req.setHeader("Accept-Encoding", "identity")
      import scala.concurrent.ExecutionContext.Implicits.global
      Await.result(asyncClient.wrapper.map(toKoushi), 1.second).websocket(req, null, new WebSocketConnectCallback {
        override def onCompleted(ex: Exception, socket: WebSocket): Unit = {
          println(s"WebSocket request finished, ex: $ex, socket: $socket")
          webSocket = Option(socket)
        }
      })

      withDelay {
        webSocket should be('defined)
      }
      webSocket.foreach(_.close())
    }

    scenario("Receive name change update") {
      val latch = new CountDownLatch(1)

      val manager = new WebSocketClient(context, asyncClient, Uri.parse(backend.pushUrl), auth)
      import EventContext.Implicits.global
      manager.onMessage {
        case resp @ JsonObjectResponse(js) if js.toString.contains("auto1_updated") =>
          println(s"Received response from websocket: '$resp'")
          latch.countDown()
        case resp =>
          println(s"Received unexpected websocket response $resp")
      }

      val c = new ZNetClient(new BasicCredentials(EmailAddress(provisionedEmail("auto1")), Some("auto1_pass")), asyncClient, backend, loginClient)
      Await.result(c(Put("/self", Json("name" -> "auto1_updated"))), 10.seconds) match {
        case Response(SuccessHttpStatus(), _, _) => //fine
        case resp => fail(s"Received unexpected response when trying to update the name: $resp")
      }

      latch.await(5, TimeUnit.SECONDS) shouldEqual true

      manager.close()
    }
  }
}
