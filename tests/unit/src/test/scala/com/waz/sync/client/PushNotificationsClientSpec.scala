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
package com.waz.sync.client

import com.waz.model.otr.ClientId
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.client.PushNotificationsClient.{LoadNotificationsResponse, NotificationsResponseEncoded}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.JsonDecoder
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet.{JsonObjectResponse, Request, Response, ZNetClient}
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.io.Source

class PushNotificationsClientSpec extends AndroidFreeSpec {

  import com.waz.sync.client.PushNotificationsClient.PagedNotificationsResponse

  feature("Deserializing push notifications") {

    scenario("parse event time") {
      val time = JsonDecoder.parseDate("2015-07-13T13:32:04.584Z").getTime
      JsonDecoder.parseDate("2015-07-13T13:32:04Z").getTime shouldEqual (time / 1000 * 1000)
      JsonDecoder.parseDate("2015-07-13T13:32:04.584285000000Z").getTime shouldEqual (time / 1000 * 1000)
    }

    scenario("parse notifications response") {
      val json = new JSONObject(Source.fromInputStream(getClass.getResourceAsStream("/events/notifications.json")).getLines().mkString("\n"))
      JsonObjectResponse(json) match {
        case PagedNotificationsResponse((notifications, false, _)) => notifications foreach { _.transient shouldEqual false }
        case _ => fail(s"notifications parsing failed for: $json")
      }
    }

    scenario("parse notifications response 1") {
      val json = new JSONObject(Source.fromInputStream(getClass.getResourceAsStream("/events/notifications1.json")).getLines().mkString("\n"))
      JsonObjectResponse(json) match {
        case PagedNotificationsResponse((_, true, _)) =>
        case _ => fail(s"notifications parsing failed for: $json")
      }
    }

    scenario("parse notifications response 2") {
      val json = new JSONObject(Notification2)
      JsonObjectResponse(json) match {
        case NotificationsResponseEncoded(_) =>
        case _ => fail(s"notifications parsing failed for: $json")
      }
    }

    scenario("Parse transient notification") {
      val n = PushNotification.NotificationDecoder(new JSONObject(TransientNotification))
      n.transient shouldEqual true
      n.events should have size 1
    }

  }

  implicit val ctx = Threading.Background
  val znet = mock[ZNetClient]

  val clientId = ClientId()

  private def mockRequest(path: String, result: LoadNotificationsResponse) =
    (znet.chainedWithErrorHandling(_: String, _: Request[Unit])(_: PartialFunction[Response, ErrorOrResponse[LoadNotificationsResponse]])(_: ExecutionContext))
    .expects(PushNotificationsClient.NotifsRequestTag, Request.Get(path), *, *)
    .returning(CancellableFuture.successful(Right(result)))

  feature("Load notifications") {
    scenario("load from start"){

      val not1 = PushNotificationEncoded.NotificationDecoder(new JSONObject(Notification1))

      mockRequest(
        PushNotificationsClient.notificationsPath(None, clientId, PushNotificationsClient.PageSize),
        LoadNotificationsResponse(Vector(not1), false, None)
      )

      val client = new PushNotificationsClientImpl(znet)

      val res = result(client.loadNotifications(None, clientId))

      res match {
        case Right(LoadNotificationsResponse(nots, hasMore, _)) =>
          hasMore shouldEqual false
          nots shouldEqual Vector(not1)
        case Left(_) => fail()
      }
    }

    scenario("load from start in pages"){
      val pageSize = 1

      val not1 = PushNotificationEncoded.NotificationDecoder(new JSONObject(Notification1))
      val not2 = PushNotificationEncoded.NotificationDecoder(new JSONObject(Notification2))

      mockRequest(
        PushNotificationsClient.notificationsPath(None, clientId, pageSize),
        LoadNotificationsResponse(Vector(not1), true, None)
      )

      mockRequest(
        PushNotificationsClient.notificationsPath(Some(not1.id), clientId, pageSize),
        LoadNotificationsResponse(Vector(not2), false, None)
      )

      val client = new PushNotificationsClientImpl(znet, pageSize)

      result(client.loadNotifications(None, clientId)) match {
        case Right(LoadNotificationsResponse(nots, hasMore, _)) =>
          hasMore shouldEqual true
          nots shouldEqual Vector(not1)
        case Left(err) => fail()
      }

      result(client.loadNotifications(Some(not1.id), clientId)) match {
        case Right(LoadNotificationsResponse(nots, hasMore, _)) =>
          hasMore shouldEqual false
          nots shouldEqual Vector(not2)
        case Left(err) => fail()
      }
    }
  }

  val Notification1 =
    """
      | {
      |         "id":"fbe54fb4-463e-4746-9861-c28c2961bdd0",
      |         "payload":[
      |            {
      |               "conversation":"3b45e65a-8bf2-447b-bd8a-03c207deae3f",
      |               "data":{
      |                  "content":"Test message 2",
      |                  "nonce":"47745f9f0-0dab-113c-43ad7ee9-394c562"
      |               },
      |               "from":"13962457-c316-4de1-9962-929c40f8cff4",
      |               "id":"f.80011231430865a7",
      |               "time":"2014-04-14T09:56:00.185Z",
      |               "type":"conversation.message-add"
      |            }
      |         ]
      |      }
    """.stripMargin

  val Notification2 =
    """
      |{
      |         "id":"1665a5e5-fa7d-4092-9b02-64f31ed265c2",
      |         "payload":[
      |            {
      |               "conversation":"3b45e65a-8bf2-447b-bd8a-03c207deae3f",
      |               "data":{
      |                  "content":"One mo re",
      |                  "nonce":"0fc4ecea8-5dcd-2117-64d1c92a-ba89a1f"
      |               },
      |               "from":"13962457-c316-4de1-9962-929c40f8cff4",
      |               "id":"10.8001123143086a45",
      |               "time":"2014-04-14T14:20:29.194Z",
      |               "type":"conversation.message-add"
      |            }
      |         ]
      |      }
    """.stripMargin

  val TransientNotification =
    """
      { "id" : "fbe54fb4-463e-4746-9861-c28c2961bdd0",
        "transient" : true,
        "payload" : [ { "conversation" : "3b45e65a-8bf2-447b-bd8a-03c207deae3f",
              "data" : { "content" : "Test message 2",
                  "nonce" : "47745f9f0-0dab-113c-43ad7ee9-394c562"
                },
              "from" : "13962457-c316-4de1-9962-929c40f8cff4",
              "id" : "f.80011231430865a7",
              "time" : "2014-04-14T09:56:00.185Z",
              "type" : "conversation.message-add"
            } ]
      }
    """
}
