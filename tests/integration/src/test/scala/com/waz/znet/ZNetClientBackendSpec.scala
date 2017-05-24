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

import com.waz.provision.ProvisionedSuite
import com.waz.service.{BackendConfig, GlobalModule}
import com.waz.threading.CancellableFuture
import com.waz.utils.Json
import com.waz.znet.Response.{HttpStatus, SuccessHttpStatus}
import org.json.JSONObject
import org.robolectric.Robolectric
import org.scalatest._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ZNetClientBackendSpec extends FeatureSpec with Matchers with ProvisionedSuite with RobolectricTests {
  import com.waz.znet.Request._

  override val provisionFile = "/three_users.json"

  lazy val Seq(client1, client2, client3) = 1 to 3 map { i =>
    new ZNetClient(provisionedEmail(s"auto$i"), s"auto${i}_pass", new AsyncClient(wrapper = TestClientWrapper()))
  }

  lazy val globalModule = new GlobalModule(Robolectric.application, BackendConfig.StagingBackend) {
    override lazy val clientWrapper: Future[ClientWrapper] = TestClientWrapper()
  }

  feature("Login user") {

    scenario("Call /self and get result") {
      val rs1 = client1(Request.Get("/self", requiresAuthentication = true))
      val rs2 = client2(Request.Get("/self", requiresAuthentication = true))

      Await.result(rs1, 5.seconds) match {
        case Response(HttpStatus(200, _), JsonObjectResponse(resp @ SelfResponse(id, name, email)), _) =>
          email shouldEqual provisionedEmail("auto1")
          name shouldEqual "auto1 user"
        case resp => fail(s"received unexpected response: $resp")
      }

      Await.result(rs2, 5.seconds) match {
        case Response(HttpStatus(200, _), JsonObjectResponse(resp @ SelfResponse(id, name, email)), _) =>
          email shouldEqual provisionedEmail("auto2")
          name shouldEqual "auto2 user"
        case resp => fail(s"received unexpected response: $resp")
      }
    }
  }

  feature("Conversation") {

    scenario("Create connection") {
      val id1 = getId(client1)
      val id2 = getId(client2)

      callJs(client1(Post("/self/connections", Json("message" -> "hello", "user" -> id2, "name" -> "conn1", "status" -> "accepted"), requiresAuthentication = true)))
      callAny(client2(Put(s"/self/connections/$id1", Json("status" -> "accepted"), requiresAuthentication = true)))
    }

    scenario("Create conversation") {
      val id1 = getId(client1)
      val id2 = getId(client2)
      val id3 = getId(client3)

      callJs(client1(Post("/self/connections", Json("message" -> "hello user 3", "user" -> id3, "name" -> "conn_2"), requiresAuthentication = true)))
      callAny(client3(Put(s"/self/connections/$id1", Json("status" -> "accepted"), requiresAuthentication = true)))
      callJs(client1(Post("/conversations", Json("users" -> Array(id2, id3)), requiresAuthentication = true)))
    }
  }

  def callJs(f: CancellableFuture[Response]) = Await.result(f, 10.seconds) match {
    case Response(SuccessHttpStatus(), JsonObjectResponse(js), _) => info(s"callJs got response: $js")
    case resp => fail(s"callJs received unexpected response: $resp")
  }

  def callAny(f: CancellableFuture[Response]) = Await.result(f, 10.seconds) match {
    case resp @ Response(SuccessHttpStatus(), _, _) => info(s"callAny got response: $resp")
    case resp => fail(s"callAny received unexpected response: $resp")
  }

  def getId(client: ZNetClient) = Await.result(client(Get("/self", requiresAuthentication = true)), 5.seconds) match {
    case Response(HttpStatus(200, _), JsonObjectResponse(SelfResponse(id, _, _)), _) => id
    case resp => fail(s"getId received unexpected response for /self: $resp")
  }

  case class SelfResponse(id: String, name: String, email: String)
  object SelfResponse {
    def unapply(js: JSONObject) = Some(js.getString("id"), js.getString("name"), js.getString("email"))
  }
}
