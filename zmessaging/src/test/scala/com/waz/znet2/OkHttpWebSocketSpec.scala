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
package com.waz.znet2

import java.net.URL

import com.waz.utils.events.EventContext
import com.waz.znet2
import com.waz.znet2.WebSocketFactory.SocketEvent
import com.waz.znet2.http.{Body, Method, Request}
import org.scalatest.{BeforeAndAfterEach, Inside, MustMatchers, WordSpec}

import scala.concurrent.duration._
import scala.util.Try

class OkHttpWebSocketSpec extends WordSpec with MustMatchers with Inside with BeforeAndAfterEach {

  import EventContext.Implicits.global
  import com.waz.BlockingSyntax.toBlocking

  private val wsPort = 8080
  private val testPath = "http://localhost:8080/test"
  private val defaultWaiting = 100
  private def testWebSocketRequest(url: String): Request[Body] = Request.create(method = Method.Get, url = new URL(url))


  import akka.NotUsed
  import akka.util.ByteString
  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import akka.stream.scaladsl.{ Flow, Source, Sink, Keep }
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model.ws.{ TextMessage, Message, BinaryMessage }
  import akka.http.scaladsl.server.Directives
  import scala.io.StdIn
  import scala.concurrent.{ Future, Promise }

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  import Directives._

  private var bindingFuture: Future[Http.ServerBinding] = _

  override protected def beforeEach(): Unit = {
    println(s"Akka-http websocket server online at http://localhost:8080/")
  }

  private def stopWebsocketServer(): Unit = {
    import system.dispatcher // for the future transformations
    bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ => system.terminate()) // and shutdown when done
  }

  override protected def afterEach(): Unit = {
    stopWebsocketServer()
  }

  "OkHttp events stream" should {

    "provide all okHttp events properly when socket closed without error." in {
      val textMessage = "Text message"
      val bytesMessage = ByteString(1, 2, 3, 4)

      // -- set up websocket server --
      // emit two messages and then close the connection
      val flowTwoMessageClose: Flow[Message, Message, NotUsed] =
          Flow.fromSinkAndSource(
            Sink.foreach[Message](println),
            Source(List(TextMessage(textMessage), BinaryMessage(bytesMessage)))
          )
      val route =
        path("test") {
          get {
            handleWebSocketMessages(flowTwoMessageClose)
          }
        }
      bindingFuture = Http().bindAndHandle(route, "localhost", wsPort)

      // -- connect and assert --
      toBlocking(znet2.OkHttpWebSocketFactory.openWebSocket(testWebSocketRequest(testPath))) { stream =>
        val firstEvent :: secondEvent :: thirdEvent :: fourthEvent :: Nil = stream.takeEvents(4)

        firstEvent mustBe an[SocketEvent.Opened]
        secondEvent mustBe an[SocketEvent.Message]
        thirdEvent mustBe an[SocketEvent.Message]
        fourthEvent mustBe an[SocketEvent.Closing]

        withClue("No events should be emitted after socket has been closed") {
          stream.waitForEvents(2.seconds) mustBe List.empty[SocketEvent]
        }
      }
    }

    "provide all okHttp events properly when socket closed with error." in {
      // -- set up websocket server --
      // emit nothing and then keep the connection open
      val flowWait: Flow[Message, Message, Promise[Option[Message]]] =
        Flow.fromSinkAndSourceMat(
          Sink.foreach[Message](println),
          Source.empty
            .concatMat(Source.maybe[Message])(Keep.right))(Keep.right)
      val route =
        path("test") {
          get {
            handleWebSocketMessages(flowWait)
          }
        }
      bindingFuture = Http().bindAndHandle(route, "localhost", wsPort)

      toBlocking(znet2.OkHttpWebSocketFactory.openWebSocket(testWebSocketRequest(testPath))) { stream =>
        val firstEvent = stream.getEvent(0)
        Try { stopWebsocketServer() } //we do not care about this error
        val secondEvent = stream.getEvent(1)

        firstEvent mustBe an[SocketEvent.Opened]
        secondEvent mustBe an[SocketEvent.Closed]

        inside(secondEvent) { case SocketEvent.Closed(_, error) =>
          error mustBe an[Some[_]]
        }

        withClue("No events should be emitted after socket has been closed") {
          stream.waitForEvents(2.seconds) mustBe List.empty[SocketEvent]
        }
      }
    }
  }

}
