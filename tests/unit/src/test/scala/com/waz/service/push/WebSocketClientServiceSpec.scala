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
package com.waz.service.push

import com.waz.RobolectricUtils
import com.waz.model.otr.ClientId
import com.waz.service._
import com.waz.testutils.DefaultPatienceConfig
import com.waz.utils.events.EventContext.Implicits.global
import com.waz.utils.events.Signal
import com.waz.znet.ZNetClient.EmptyClient
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest._
import com.waz.ZLog.ImplicitTag._
import com.waz.content.GlobalPreferences

import scala.concurrent.duration._

@Ignore class WebSocketClientServiceSpec extends FeatureSpec with Matchers with RobolectricTests with BeforeAndAfter with RobolectricUtils with ScalaFutures with DefaultPatienceConfig with MockFactory {

  val timeout = 250.millis
  val timeouts = new Timeouts {
    override val webSocket: WebSocket = new WebSocket {
      override def inactivityTimeout: Timeout = timeout

      override def connectionTimeout: Timeout = timeout
    }
  }

  lazy val lifecycle: ZmsLifeCycle = new ZmsLifeCycleImpl
  lazy val network = new DefaultNetworkModeService(context, lifecycle)
  lazy val prefs = GlobalPreferences(context)
  lazy val meta = new MetaDataService(context)

//  lazy val service = new WebSocketClientService(context, lifecycle, new EmptyClient, network, BackendConfig.StagingBackend, ClientId(), timeouts, gcm)


//  feature("active client") {
//    lazy val sub = service.client { c => println(s"client changed: $c") }
//    def client = {
//      sub
//      service.client.head.futureValue
//    }
//
//    scenario("client is created when id is set and lifecycle is active") {
//      lifecycle.lifecycleState ! LifecycleState.Active
//
//      client shouldBe 'defined
//    }
//
//    scenario("client is not destroyed if lifecycle is paused for short time") {
//      (gcm.gcmActive _ ).expects().anyNumberOfTimes().returning(Signal const true)
//      lifecycle.lifecycleState ! LifecycleState.Idle
//
//      awaitUi(50.millis)
//      client shouldBe 'defined
//
//      lifecycle.lifecycleState ! LifecycleState.Active
//      awaitUi(250.millis)
//      client shouldBe 'defined
//    }
//
//    scenario("client is destroyed after delay when lifecycle is paused") {
//      (gcm.gcmActive _ ).expects().anyNumberOfTimes().returning(Signal const true)
//      lifecycle.lifecycleState ! LifecycleState.Idle
//
//      awaitUi(50.millis)
//      client shouldBe 'defined
//
//      withDelay {
//        client shouldBe empty
//      }
//    }
//  }
//
//  feature("Connection error signaling") {
//
//    @volatile var error = false
//
//    scenario("report error when client stays unconnected for 3 seconds") {
//      service.connectionError {
//        error = _
//      }
//      lifecycle.lifecycleState ! LifecycleState.UiActive
//
//      service.wsActive.currentValue shouldBe 'defined
//      awaitUi(50.millis)
//
//      error shouldEqual false
//
//      withDelay {
//        service.connectionError.currentValue shouldEqual Some(true)
//        error shouldEqual true
//      }
//    }
//  }
}
