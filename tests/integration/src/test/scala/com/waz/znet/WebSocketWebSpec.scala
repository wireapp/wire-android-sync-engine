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

import com.koushikdutta.async.callback.DataCallback
import com.koushikdutta.async.http.AsyncHttpClient.{StringCallback, WebSocketConnectCallback}
import com.koushikdutta.async.http._
import com.koushikdutta.async.{ByteBufferList, DataEmitter}
import com.waz.RobolectricUtils
import com.waz.test.WebSocketEchoServer
import com.waz.provision.RemoteProcess
import org.robolectric.shadows.ShadowLog
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class WebSocketWebSpec extends FeatureSpecLike with Matchers with BeforeAndAfter with BeforeAndAfterAll with RobolectricTests with RobolectricUtils {
  implicit val timeout: Timeout = 15.seconds

  var client: AsyncClient = _
  var cl: AsyncHttpClient = _
  var echo: sys.process.Process = _

  before {
    ShadowLog.stream = System.out
    client = new AsyncClient(wrapper = TestClientWrapper())
  }

  after {
    client.close()
    if (cl != null) cl.getServer.stop()
  }


  override protected def beforeAll(): Unit = {
    super.beforeAll()

    echo = RemoteProcess[WebSocketEchoServer]("18084") // start WebSocketEchoServer
    awaitUi(5.seconds)
  }


  override protected def afterAll(): Unit = {
    echo.destroy()
    super.afterAll()
  }

  val NullStringCallback = new StringCallback {
    override def onCompleted(p1: Exception, p2: AsyncHttpResponse, p3: String): Unit = {}
  }
  
  feature("websocket") {

    scenario("Connect to echo server: http//localhost:18084") {
      import scala.concurrent.ExecutionContext.Implicits.global
      cl = Await.result(client.wrapper.map(ClientWrapper.toKoushi), 1.second)
      @volatile var socket: WebSocket = null

      cl.websocket(new AsyncHttpGet("http://localhost:18084"), null, new WebSocketConnectCallback {
        override def onCompleted(ex: Exception, ws: WebSocket): Unit = {
          println(s"connected $ex, $ws")
          if (ex != null) throw ex

          socket = ws
        }
      })

      withDelay { socket should not be null }

      var stringResp: String = null

      socket.setStringCallback(new WebSocket.StringCallback {
        override def onStringAvailable(p1: String): Unit = stringResp = p1
      })
      socket.setDataCallback(new DataCallback {
        override def onDataAvailable(p1: DataEmitter, p2: ByteBufferList): Unit = println("got data")
      })

      socket.send("test")
      withDelay(stringResp shouldEqual "test")

      socket.send("ping: test")
      withDelay(stringResp shouldEqual "pong: test")
    }
  }
}
