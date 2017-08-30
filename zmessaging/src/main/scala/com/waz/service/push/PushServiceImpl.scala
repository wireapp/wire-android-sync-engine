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

import android.content.Context
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ErrorResponse.{ConnectionErrorCode, TimeoutCode}
import com.waz.content.UserPreferences
import com.waz.content.UserPreferences.LastStableNotification
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.EventPipeline
import com.waz.service.push.PushService.SlowSyncRequest
import com.waz.sync.client.EventsClient.NotificationsResponse
import com.waz.sync.client.{EventsClient, PushNotification}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils._
import com.waz.utils.events._
import org.threeten.bp.{Duration, Instant}

import scala.concurrent.{ExecutionContext, Future, Promise}

trait PushService {

  def cloudPushNotificationsToProcess: SourceSignal[Set[Uid]]

  def syncHistory(): Future[Unit]

}

class PushServiceImpl(context: Context, keyValue: UserPreferences, client: EventsClient, clientId: ClientId, signals: PushServiceSignals, pipeline: EventPipeline, webSocket: WebSocketClientService) extends PushService {
  self =>
  private implicit val dispatcher = new SerialDispatchQueue(name = "PushService")
  private implicit val ec = EventContext.Global

  private val wakeLock = new WakeLock(context)

  val lastNotification = new LastNotificationIdService(keyValue, signals, client, clientId)

  var connectedPushPromise = Promise[PushServiceImpl]()

  lazy val processing = signals.processing
  override val cloudPushNotificationsToProcess = Signal(Set[Uid]())

  /**
    * Drift to the BE time at the moment we fetch notifications
    * Used for calling (time critical) messages that can't always rely on local time, since the drift can be
    * greater than the window in which we need to respond to messages
    */
  val beDrift = Signal(Duration.ZERO).disableAutowiring()

  webSocket.connected {
    signals.pushConnected ! _
  }

  client.onNotificationsPageLoaded.on(dispatcher) { resp =>
    onPushNotifications(resp.notifications)
    resp.beTime.foreach { beTime =>
      beDrift ! Instant.now.until(beTime)
    }
    if (resp.lastIdWasFound) debug(s"Loaded page of ${resp.notifications.size} notifications")
    else {
      info(s"server couldn't provide all missing notifications, will schedule slow sync, after processing available events")
      signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis(), lostHistory = true)
    }
  }

  private var subs = Seq.empty[Subscription]

  webSocket.client.on(dispatcher) {
    case None =>
      subs.foreach(_.destroy())
      subs = Nil

    case Some(ws) =>
      subs.foreach(_.destroy())
      subs = Seq(
        ws.onMessage { content =>
          verbose(s"got websocket message: $content")
          content match {
            case NotificationsResponse(notifications@_*) =>
              debug(s"got notifications from data: $content")
              onPushNotifications(notifications)
            case resp =>
              error(s"unexpected push response: $resp")
          }
        },
        ws.onError { ex =>
          warn(s"some PushNotification processing failed, will schedule slow sync", ex)
          signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis())
        }
      )
  }

  webSocket.connected {
    case true =>
      debug("onConnected")
      syncHistory()
      connectedPushPromise.trySuccess(self)
    case false =>
      debug("onDisconnected")
      syncHistory()
      connectedPushPromise.tryFailure(new Exception("onDisconnected"))
      connectedPushPromise = Promise()
  }

  //no need to sync history on cloud messaging if websocket is connected
  webSocket.connected.flatMap {
    case false => cloudPushNotificationsToProcess
    case _ => Signal.empty[Set[Uid]]
  }.on(dispatcher) {
    notifications =>
      if (notifications.nonEmpty){
        verbose("Sync history in response to cloud message notification")
        syncHistory()
      }
  }

  def onPushNotification(n: PushNotification) = onPushNotifications(Seq(n)) //used in tests

  private def onPushNotifications(allNs: Seq[PushNotification]): Unit = if (allNs.nonEmpty) {
    debug(s"gotPushNotifications: ${allNs.size}")

    val ns = allNs.filter(_.hasEventForClient(clientId))

    val processing = processNotifications(ns) //careful not to inline this - needs to start executing whether transient or not!
    ns.lift(ns.lastIndexWhere(!_.transient)).foreach { n =>
      lastNotification.updateLastIdOnNotification(n.id, processing)
    }
  }

  private def processNotifications(notifications: Seq[PushNotification]) = wakeLock.async {
    signals.processing ! true
    pipeline {
      returning(notifications.flatMap(_.eventsForClient(clientId))) {
        _.foreach { ev =>
          ev.withCurrentLocalTime()
          verbose(s"event: $ev")
        }
      }
    }.map {
      _ => cloudPushNotificationsToProcess mutate (_ -- notifications.map(_.id).toSet)
    }.andThen {
      case _ => signals.processing ! false
    }
  }

  override def syncHistory() =
    lastNotification.lastNotificationId() flatMap {
      case None =>
        debug("no last notification Id on connect, will schedule slow sync")
        Future.successful(signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis()))
      case Some(LastNotificationIdService.NoNotificationsId) =>
        verbose(s"no last notification available, will try loading all notifications")
        loadNotifications(None)
      case Some(id) =>
        verbose(s"found last notification: $id, will load notifications")
        loadNotifications(Some(id))
    }

  private def loadNotifications(lastId: Option[Uid]): CancellableFuture[Unit] =
    client.loadNotifications(lastId, clientId, pageSize = EventsClient.PageSize).flatMap {
      case Left(ErrorResponse(TimeoutCode | ConnectionErrorCode, _, _)) =>
        warn(s"/notifications failed due to timeout or connection error. Do nothing and wait for next notification")
        CancellableFuture.successful(())
      case Left(err) =>
        warn(s"/notifications failed with unexpected error: $err, will schedule slow sync")
        CancellableFuture.successful(signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis()))
      case _ => CancellableFuture.successful(()) //notifications results handled by event stream due to paging
    }
}

object PushService {
  // lostHistory is set if there were lost notifications, meaning that some messages might be lost
  case class SlowSyncRequest(time: Long, lostHistory: Boolean = false)
}

class PushServiceSignals {
  val onSlowSyncNeeded = new SourceSignal[SlowSyncRequest] with BgEventSource
  val pushConnected = Signal[Boolean]()
  val processing = Signal(false)

  def afterProcessing[T](f : => Future[T])(implicit ec: ExecutionContext): Future[T] = processing.filter(_ == false).head.flatMap(_ => f)
}

/**
  * Keeps track of last received notifications and updates lastNotificationId preference accordingly.
  * Last id is fetched from backend whenever slow sync is requested.
  */
class LastNotificationIdService(userPrefs: UserPreferences, signals: PushServiceSignals, client: EventsClient, clientId: ClientId) {

  import LastNotificationIdService._

  private implicit val dispatcher = new SerialDispatchQueue(name = "LastNotificationIdService")
  private implicit val ec = EventContext.Global

  import userPrefs._

  private var fetchLast = CancellableFuture.successful(())

  private[push] val idPref = preference(LastStableNotification)

  def lastNotificationId() = Future(idPref()).flatten // future and flatten ensure that there is no race with onNotification

  signals.pushConnected.onChanged.filter(_ == false).on(dispatcher)(_ => fetchLast.cancel())

  signals.onSlowSyncNeeded.on(dispatcher) { _ =>
    verbose(s"onSlowSync()")
    fetchLast = client.loadLastNotification(clientId) map {
      case Right(Some(notification)) =>
        verbose(s"fetched last notification: $notification")
        idPref := Some(notification.id)
        verbose(s"updated pref from backend on slow sync: ${notification.id}")
      case Right(None) =>
        verbose(s"no last notification available on backend")
        idPref := Some(NoNotificationsId)
      case Left(error) =>
        warn(s"/notifications/last failed with error: $error")
    }
  }

  def updateLastIdOnNotification(id: Uid, processing: Future[Any]): Unit = {
    fetchLast.cancel()
    processing.onSuccess {
      case _ =>
        idPref := Some(id)
        verbose(s"updated last id: $id")
    }
  }
}

object LastNotificationIdService {
  val NoNotificationsId = Uid(0, 0)
}
