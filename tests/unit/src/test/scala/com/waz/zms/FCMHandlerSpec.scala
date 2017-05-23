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
package com.waz.zms

import com.waz.model.otr.ClientId
import com.waz.model.{ConvId, Uid, UserId}
import com.waz.service.ZmsLifecycle
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.otr.OtrService
import com.waz.service.push.PushService
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.zms.FCMHandlerService.FCMHandler
import org.json
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.Future

class FCMHandlerSpec extends AndroidFreeSpec {

  val otrService = mock[OtrService]
  val lifecycle = mock[ZmsLifecycle]
  val push = mock[PushService]
  val self = UserId()
  val convsContent = mock[ConversationsContentUpdater]
  val sync = mock[SyncServiceHandle]

  var lifecycleActive = Signal(false)
  var cloudNotsToHandle = Signal(Set.empty[Uid])

  after {
    lifecycleActive = Signal(false)
    cloudNotsToHandle = Signal(Set.empty[Uid])
  }

  feature("Parse notifications") {

    scenario("Handle regular, non encrypted FCM message when app is in background") {
      val notId = Uid()
      val fcm = plainPayload(id = notId)

      val handler = initHandler
      handler.handleMessage(fcm)
      result(cloudNotsToHandle.filter(_.contains(notId)).head)
    }

    scenario("Ignore notifications with different intended user id") {
      val fcm = plainPayload(intended = UserId())

      val handler = initHandler
      handler.handleMessage(fcm)

      awaitAllTasks
      result(cloudNotsToHandle.filter(_.isEmpty).head)
    }


    scenario("Decrypt cipher notification") {

      val notId = Uid()
      val fcm = cipherPayload()

      val decryptedValue = returning(new json.JSONObject())(o => o.put("data", new json.JSONObject(payload(id = notId))))

      (otrService.decryptGcm _).expects(*, *).once().returning(Future.successful(Some(decryptedValue)))

      val handler = initHandler
      handler.handleMessage(fcm)
      result(cloudNotsToHandle.filter(_.contains(notId)).head)

    }
  }

  def plainPayload(intended: UserId = self, id: Uid = Uid()) =
    Map(
      "type" -> "plain",
      "user" -> intended.str,
      "data" -> payload(id = id)
    )

  def payload(sender:    ClientId = ClientId(),
              recipient: ClientId = ClientId(),
              text:      String   = "",
              from:      UserId   = UserId(),
              time:      Instant  = Instant.now(),
              conv:      ConvId   = ConvId(),
              id:        Uid      = Uid(),
              //TODO would be nice to have event names encoded in Event subclasses themselves
              eventTpe:  String   = "conversation.otr-message-add") =
    s"""
       |{
       |  "payload":
       |    [{"data": {
       |        "sender":"${sender.str}",
       |        "recipient":"$recipient.str}",
       |        "text":"$text"
       |    },
       |    "from":"${from.str}",
       |    "time":"${time.toString}",
       |    "type":"$eventTpe",
       |    "conversation":"${conv.str}"
       |  }],
       |  "transient":false,
       |  "id":"${id.str}"
       |}
      """.stripMargin


  def cipherPayload() =
    Map(
      "type" -> "cipher",
      "mac"  -> "abcd",
      "data" -> "abcd"
    )


  def initHandler = {
    (lifecycle.active _).expects().anyNumberOfTimes().returning(lifecycleActive)
    (push.cloudPushNotificationsToProcess _).expects().anyNumberOfTimes().returning(cloudNotsToHandle)
    new FCMHandler(otrService, lifecycle, push, self, convsContent, sync)
  }
}
