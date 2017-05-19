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

import com.koushikdutta.async.{AsyncServer, ByteBufferList, DataEmitter}
import com.koushikdutta.async.callback.DataCallback
import com.koushikdutta.async.http._
import com.koushikdutta.async.http.AsyncHttpClient.{StringCallback, WebSocketConnectCallback}
import com.waz.utils.{IoUtils, returning}
import com.waz.utils.wrappers.URI
import com.waz.znet.ContentEncoder.{BinaryRequestContent, StreamRequestContent}
import com.waz.znet.Response.HttpStatus
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, Matchers, RobolectricTests}
import java.io.{File, FileInputStream}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.waz.testutils.DefaultPatienceConfig
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._

class AsyncClientWebSpec extends FeatureSpecLike with Matchers with BeforeAndAfter with RobolectricTests with ScalaFutures with DefaultPatienceConfig {

  var client: AsyncClient = _
  var cl: AsyncHttpClient = _

  before {
    client = new AsyncClient(wrapper = TestClientWrapper())
  }

  after {
    client.close()
    if (cl != null) cl.getServer.stop()
  }

  val NullStringCallback = new StringCallback {
    override def onCompleted(p1: Exception, p2: AsyncHttpResponse, p3: String): Unit = {}
  }
  
  feature("Get") {

    scenario("AsyncHttpClient get https://www.wire.com") {
      import scala.concurrent.ExecutionContext.Implicits.global
      cl = TestClientWrapper(new AsyncHttpClient(new AsyncServer)).map(ClientWrapper.toKoushi).futureValue
      val r = new AsyncHttpGet("https://www.wire.com")
      val ret = cl.executeString(r, NullStringCallback)
      println("First request returned: " + ret.get(15, TimeUnit.SECONDS).substring(0, 256))

      val req = new AsyncHttpGet("https://www.wire.com")
      val ret1 = cl.executeString(req, NullStringCallback)
      println("Second request returned: " + ret1.get(15, TimeUnit.SECONDS).substring(0, 256))
    }

    scenario("AsyncHttpClient https get") {
      import scala.concurrent.ExecutionContext.Implicits.global
      cl = TestClientWrapper(new AsyncHttpClient(new AsyncServer)).map(ClientWrapper.toKoushi).futureValue
      @volatile var ex: Exception = null
      val ret = cl.executeString(new AsyncHttpGet("https://www.wire.com/"), new StringCallback() {
        override def onCompleted(e: Exception, source: AsyncHttpResponse, result: String): Unit = {
          ex = e
        }
      })
      ret.get(25, TimeUnit.SECONDS)
      Option(ex) shouldEqual None
    }

    scenario("GET https from https://wire.com") {
      Await.result(client(URI.parse("https://www.wire.com"), Request.Get("")), 5.second) match {
        case Response(HttpStatus(200, _), _, _) => //expected
        case res => fail(s"got unexpected response: $res")
      }

      Await.result(client(URI.parse("https://www.wire.com"), Request.Get("")), 5.second) match {
        case Response(HttpStatus(200, _), _, _) => //expected
        case res => fail(s"got unexpected response: $res")
      }
    }

    scenario("Get gzipped content from www.gradle.com") {
      Await.result(client(URI.parse("http://www.gradle.com"), Request.Get("")), 5.second) match {
        case Response(HttpStatus(200, _), _, _) => //expected
        case res => fail(s"got unexpected response: $res")
      }
    }
    scenario("Get gzipped chunked content from www.clockworkmod.com") {
      Await.result(client(URI.parse("http://www.clockworkmod.com"), Request.Get("")), 5.second) match {
        case Response(HttpStatus(200, _), _, _) => //expected
        case res => fail(s"got unexpected response: $res")
      }
    }
  }

  feature("Post") {

    scenario("post data") {
      val data = (0 to 100).map("test_" + _).mkString(", ").getBytes("utf8")

      Await.result(client(URI.parse("http://posttestserver.com/post.php"), Request.Post("/post.php", new BinaryRequestContent(data, "text/plain"))), 45.seconds) match {
        case Response(HttpStatus(200, _), _, _) => //expected
        case res => fail(s"got unexpected response: $res")
      }
    }

    scenario("post file with len") {
      val file = returning(File.createTempFile("meep", "png"))(_.deleteOnExit())
      IoUtils.copy(getClass.getResourceAsStream("/images/penguin.png"), file)
      Await.result(client(URI.parse("http://posttestserver.com/post.php"), Request.Post("/post.php", new StreamRequestContent(new FileInputStream(file), "image/png", file.length.toInt))), 45.seconds) match {
        case Response(HttpStatus(200, _), _, _) => //expected
        case res => fail(s"got unexpected response: $res")
      }
    }
  }

  feature("websocket") {
    scenario("Connect to echo server: ws://echo.websocket.org/") {
      val latch = new CountDownLatch(1)
      import scala.concurrent.ExecutionContext.Implicits.global
      cl = Await.result(client.wrapper.map(ClientWrapper.toKoushi), 1.second)
      cl.websocket(new AsyncHttpGet("https://echo.websocket.org/"), null, new WebSocketConnectCallback {
        override def onCompleted(ex: Exception, ws: WebSocket): Unit = {
          println(s"connected $ex, $ws")
          if (ex != null) throw ex

          ws.setStringCallback(new WebSocket.StringCallback {
            override def onStringAvailable(p1: String): Unit = if (p1 == "test") latch.countDown()
          })
          ws.setDataCallback(new DataCallback {
            override def onDataAvailable(p1: DataEmitter, p2: ByteBufferList): Unit = println("got data")
          })

          ws.send("test")
        }
      })

      latch.await(15, TimeUnit.SECONDS) shouldEqual true
    }
  }
}
