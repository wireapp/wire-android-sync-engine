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

import com.waz.ZLog._
import com.waz.model.otr.ClientId
import com.waz.service.push.GcmGlobalService.PushSenderId
import com.waz.threading.Threading
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.Response.SuccessHttpStatus
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet._
import org.json.JSONObject

import scala.util.Try

class GcmClient(netClient: ZNetClient) {
  import Threading.Implicits.Background
  import com.waz.sync.client.GcmClient._
  private implicit val tag: LogTag = logTagFor[GcmClient]

  def postPushToken(token: GcmToken): ErrorOrResponse[GcmToken] =
    netClient.withErrorHandling("postPushToken", Request.Post(PushesPath, token)) {
      case Response(SuccessHttpStatus(), GcmResponseExtractor(res), _) => res
    }

  def deletePushToken(token: String): ErrorOrResponse[Unit] =
    netClient.updateWithErrorHandling("deletePushToken", Request.Delete[Unit](s"$PushesPath/$token"))
}

object GcmClient {
  val PushesPath = "/push/tokens"

  case class GcmToken(token: String, app: PushSenderId, clientId: ClientId, transport: String = "GCM")
  object GcmToken {

    implicit lazy val Decoder: JsonDecoder[GcmToken] = new JsonDecoder[GcmToken] {
      import com.waz.utils.JsonDecoder._
      override def apply(implicit js: JSONObject): GcmToken = GcmToken('token, 'app, decodeId[ClientId]('client), 'transport)
    }

    implicit lazy val Encoder: JsonEncoder[GcmToken] = new JsonEncoder[GcmToken] {
      override def apply(v: GcmToken): JSONObject = JsonEncoder { o =>
        o.put("token", v.token)
        o.put("app", v.app.str)
        o.put("transport", v.transport)
        o.put("client", v.clientId.str)
      }
    }

    implicit val TokenContentEncoder: ContentEncoder[GcmToken] = ContentEncoder.json[GcmToken]
  }

  object GcmResponseExtractor {
    def unapplySeq(resp: ResponseContent): Option[Seq[GcmToken]] = resp match {
      case JsonArrayResponse(js) => Try(JsonDecoder.array[GcmToken](js)).toOption
      case JsonObjectResponse(js) => Try(Seq(GcmToken.Decoder(js))).toOption
      case _ => None
    }
  }
}
