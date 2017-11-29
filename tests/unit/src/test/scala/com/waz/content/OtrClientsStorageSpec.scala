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
package com.waz.content

import com.waz.RobolectricUtils
import com.waz.api.{OtrClientType, Verification}
import com.waz.model.otr.{Client, ClientId, Location, SignalingKey, UserClients}
import com.waz.model.{AESKey, UserId}
import org.robolectric.Robolectric
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.threeten.bp.Instant

import scala.concurrent.duration._

@Ignore class OtrClientsStorageSpec extends FeatureSpec with Matchers with BeforeAndAfter with RobolectricTests with RobolectricUtils with ScalaFutures { test =>

  implicit val defaultPatience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  lazy val user = UserId()
  lazy val storage = new ZmsDatabase(user, Robolectric.application)
  lazy val clients = new OtrClientsStorageImpl(Robolectric.application, storage)

  feature("clients storage") {

    lazy val client1 = Client(ClientId("client1"), "client1", "model", Some(Instant.now), Some(Location(10, -20, "Berlin, DE")), Some("address"), Some(SignalingKey(AESKey("enc"), "mac")), Verification.VERIFIED, OtrClientType.DESKTOP)
    lazy val client2 = Client(ClientId("client2"), "client2")
    lazy val cs = Seq(client1, client2)
    lazy val ucs = UserClients(user, cs.map(c => c.id -> c).toMap)

    scenario("save clients") {
      clients.insert(ucs).futureValue shouldEqual ucs

      clients.get(user).futureValue shouldEqual Some(ucs)
    }

    scenario("load clients form second clients storage") {
      awaitUi(1.second)
      new OtrClientsStorageImpl(Robolectric.application, storage).get(user).futureValue shouldEqual Some(ucs)
    }
  }
}
