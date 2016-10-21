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
import com.waz.api.impl.ErrorResponse
import com.waz.model.otr.ClientId
import com.waz.model.{ConversationEvent, Event, OtrEvent, Uid}
import com.waz.threading.Threading
import com.waz.utils.JsonDecoder
import com.waz.utils.JsonDecoder._
import com.waz.utils.events.SourceStream
import com.waz.znet.Response.{ErrorStatus, HttpStatus, Status, SuccessHttpStatus}
import com.waz.znet.ZNetClient.{ErrorOr, ErrorOrResponse}
import com.waz.znet.{JsonObjectResponse, _}
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.util.control.NonFatal

class EventsClient(netClient: ZNetClient) {

  import Threading.Implicits.Background
  import com.waz.sync.client.EventsClient._

  val onNotificationsPageLoaded = new SourceStream[LoadNotificationsResponse]

  private var currentRequest: ErrorOr[(Option[Uid], Boolean)] = Future.successful(Right(Option.empty[Uid], false))

  def loadNotifications(since: Option[Uid], client: ClientId, pageSize: Int): ErrorOr[Option[Uid]] = {

    def loadNextPage(since: Option[Uid], isFirstPage: Boolean): ErrorOr[(Option[Uid], Boolean)] = {
      netClient.chainedFutureWithErrorHandling("loadNotifications", Request.Get(notificationsPath(since, client, pageSize))) {
        case Response(status, PagedNotificationsResponse((notifications, hasMore)), _) =>

          onNotificationsPageLoaded ! LoadNotificationsResponse(notifications, lastIdWasFound = !isFirstPage || status.isSuccess)

          if (hasMore) loadNextPage(since = notifications.lastOption.map(_.id), isFirstPage = false)
          else Future.successful(Right(notifications.lastOption map (_.id), false))
      }
    }

    currentRequest = if (currentRequest.isCompleted) loadNextPage(since, isFirstPage = true) else currentRequest
    currentRequest.map(_.right.map(_._1))
  }

  def loadLastNotification(client: ClientId): ErrorOrResponse[Option[PushNotification]] =
    netClient(Request.Get(lastNotificationPath(client))) map {
      case Response(SuccessHttpStatus(), NotificationsResponse(notification), _) => Right(Some(notification))
      case Response(HttpStatus(Status.NotFound, _), ErrorResponse(404, _, "not-found"), _) => Right(None)
      case Response(ErrorStatus(), ErrorResponse(code, msg, label), _) =>
        warn(s"Error response to loadLastNotification: ${ErrorResponse(code, msg, label)}")
        Left(ErrorResponse(code, msg, label))
      case resp@Response(_, _, _) =>
        error(s"Unexpected response to loadLastNotifications: $resp")
        Left(ErrorResponse(resp.status.status, "unexpected", "internal-error"))

    }

  private def lastNotificationPath(client: ClientId) = Request.query(LastNotificationPath, "client" -> client.str)

  private def notificationsPath(since: Option[Uid], client: ClientId, pageSize: Int) = {
    val args = Seq("since" -> since, "client" -> Some(client), "size" -> Some(pageSize)) collect { case (key, Some(v)) => key -> v }
    Request.query(NotificationsPath, args: _*)
  }
}

case class PushNotification(id: Uid, events: Seq[Event], transient: Boolean = false) {

  /**
    * Check if notification contains events intended for current client. In some (rare) cases it may happen that
    * BE sends us notifications intended for different device, we can verify that by checking recipient field.
    * Unencrypted events are always considered to belong to us.
    */
  def hasEventForClient(clientId: ClientId) = events.forall(forUs(clientId, _))

  def eventsForClient(clientId: ClientId) = events.filter(forUs(clientId, _))

  private def forUs(clientId: ClientId, event: Event) = event match {
    case ev: OtrEvent => clientId == ev.recipient
    case _ => true
  }

  def lastConvEventTime = {
    var max = 0L
    events foreach {
      case e: ConversationEvent => max = math.max(max, e.time.getTime)
      case _ =>
    }
    Instant ofEpochMilli max
  }
}

object PushNotification {
  implicit lazy val NotificationDecoder: JsonDecoder[PushNotification] = new JsonDecoder[PushNotification] {

    import com.waz.utils.JsonDecoder._

    override def apply(implicit js: JSONObject): PushNotification =
      PushNotification('id, array[Event](js.getJSONArray("payload")), 'transient)
  }
}

object EventsClient {
  private implicit val logTag: LogTag = logTagFor[EventsClient]
  val NotificationsPath = "/notifications"
  val LastNotificationPath = "/notifications/last"
  val PageSize = 1000

  case class LoadNotificationsResponse(notifications: Vector[PushNotification], lastIdWasFound: Boolean)

  object NotificationsResponse {
    def unapplySeq(response: ResponseContent): Option[Seq[PushNotification]] = try response match {
      case JsonObjectResponse(js) => Some(Vector(implicitly[JsonDecoder[PushNotification]].apply(js)))
      case JsonArrayResponse(js) => Some(arrayColl[PushNotification, Vector](js))
      case _ => None
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse push notification(s) from response: $response", e)
        None
    }
  }

  object PagedNotificationsResponse {

    import com.waz.utils.JsonDecoder._

    def unapply(response: ResponseContent): Option[(Vector[PushNotification], Boolean)] = try response match {
      case JsonObjectResponse(js) if js.has("notifications") => Some((arrayColl[PushNotification, Vector](js.getJSONArray("notifications")), decodeBool('has_more)(js)))
      case JsonArrayResponse(js) => Some((arrayColl[PushNotification, Vector](js), false))
      case _ => None
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse paged push notifications from response: $response", e)
        None
    }
  }

}
