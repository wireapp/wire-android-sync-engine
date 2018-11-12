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

import com.waz.ZLog._
import com.waz.content.GlobalPreferences.BackendDrift
import com.waz.content.UserPreferences.LastStableNotification
import com.waz.content.{GlobalPreferences, UserPreferences}
import com.waz.model.Event.EventDecoder
import com.waz.model._
import com.waz.model.sync.SyncCommand
import com.waz.service.ZMessaging.{accountTag, clock}
import com.waz.service._
import com.waz.service.otr.OtrService
import com.waz.service.tracking.{MissedPushEvent, TrackingService}
import com.waz.sync.client.PushNotificationEncoded
import com.waz.sync.{SyncRequestService, SyncServiceHandle}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.{Signal, _}
import com.waz.utils.{RichInstant, _}
import org.json.JSONObject
import org.threeten.bp.{Duration, Instant}

import scala.concurrent.Future
import scala.concurrent.duration._

/** PushService handles notifications coming from FCM, WebSocket, and fetch.
  * We assume FCM notifications are unreliable, so we use them only as information that we should perform a fetch (syncHistory).
  * A notification from the web socket may trigger a fetch on error. When fetching we ask BE to send us all notifications since
  * a given lastId - it may take time and we even may find out that we lost some history (lastId not found) which triggers slow sync.
  * So, it may happen that during the fetch new notifications will arrive through the web socket. Their processing should be
  * performed only after the fetch is done. For that we use a serial dispatch queue and we process notifications in futures,
  * which are put at the end of the queue, essentially making PushService synchronous.
  * Also, we need to handle fetch failures. If a fetch fails, we want to repeat it - after some time, or on network change.
  * In such case, new web socket notifications waiting to be processed should be dismissed. The new fetch will (if successful)
  * receive them in the right order.
  */

trait PushNotificationService {

  def lastKnownNotificationId: Signal[Option[Uid]]

  def onNotificationsResponse(notifications: Vector[PushNotificationEncoded], trigger: Option[Uid], time: Option[Instant], firstSync: Boolean, historyLost: Boolean): Future[Unit]

  def processNotifications(): Future[Unit]

  def onHistoryLost: SourceSignal[Instant] with BgEventSource
  def processing: Signal[Boolean]
  def waitProcessing: Future[Unit]

  /**
    * Drift to the BE time at the moment we fetch notifications
    * Used for calling (time critical) messages that can't always rely on local time, since the drift can be
    * greater than the window in which we need to respond to messages
    */
  def beDrift: Signal[Duration]
}

class PushNotificationServiceImpl(selfUserId:           UserId,
                                  userPrefs:            UserPreferences,
                                  prefs:                GlobalPreferences,
                                  receivedPushes:       ReceivedPushStorage,
                                  notificationStorage:  PushNotificationEventsStorage,
                                  pipeline:             EventPipeline,
                                  otrService:           OtrService,
                                  wsPushService:        WSPushService,
                                  network:              NetworkModeService,
                                  lifeCycle:            UiLifeCycle,
                                  tracking:             TrackingService,
                                  syncService:          SyncRequestService,
                                  sync:                 SyncServiceHandle)
                                 (implicit ev: AccountContext) extends PushNotificationService { self =>
  import PushNotificationService._

  implicit val logTag: LogTag = accountTag[PushNotificationServiceImpl](selfUserId)
  import Threading.Implicits.Background

  override val onHistoryLost =
    new SourceSignal[Instant] with BgEventSource

  override lazy val processing =
    syncService.isSyncing(selfUserId, Seq(SyncCommand.ProcessNotifications))

  override def waitProcessing =
    processing.filter(_ == false).head.map(_ => {})

  private lazy val beDriftPref =
    prefs.preference(BackendDrift)

  override lazy val beDrift =
    beDriftPref.signal.disableAutowiring()

  //Should be impossible to fail a sync job? We have to retry until the end of time!
  private lazy val fetchInProgress =
    syncService.isSyncing(selfUserId, Seq(SyncCommand.SyncNotifications))

  private lazy val idPref =
    userPrefs.preference(LastStableNotification)

  override def lastKnownNotificationId =
    idPref.signal

  override def processNotifications() = {

    def isOtrEventJson(ev: JSONObject) =
      ev.getString("type").equals("conversation.otr-message-add")

    def processEncryptedRows() =
      notificationStorage.encryptedEvents.flatMap { rows =>
        verbose(s"Processing ${rows.size} encrypted rows")
        Future.sequence(rows.map { row =>
          if (!isOtrEventJson(row.event)) notificationStorage.setAsDecrypted(row.index)
          else {
            val otrEvent = ConversationEvent.ConversationEventDecoder(row.event).asInstanceOf[OtrEvent]
            val writer = notificationStorage.writeClosure(row.index)
            otrService.decryptStoredOtrEvent(otrEvent, writer).flatMap {
              case Left(Duplicate) =>
                verbose("Ignoring duplicate message")
                notificationStorage.remove(row.index)
              case Left(error) =>
                val e = OtrErrorEvent(otrEvent.convId, otrEvent.time, otrEvent.from, error)
                verbose(s"Got error when decrypting: ${e.toString}\nOtrError: ${error.toString}")
                notificationStorage.writeError(row.index, e)
              case Right(_) => Future.successful(())
            }
          }
        })
      }

    def processDecryptedRows(): Future[Unit] = {
      def decodeRow(event: PushNotificationEvent) =
        if(event.plain.isDefined && isOtrEventJson(event.event)) {
          val msg = GenericMessage(event.plain.get)
          val msgEvent = ConversationEvent.ConversationEventDecoder(event.event)
          otrService.parseGenericMessage(msgEvent.asInstanceOf[OtrMessageEvent], msg)
        } else {
          Some(EventDecoder(event.event))
        }

      notificationStorage.getDecryptedRows().flatMap { rows =>
        verbose(s"Processing ${rows.size} rows")
        if (rows.nonEmpty)
          for {
            _ <- pipeline(rows.flatMap(decodeRow))
            _ <- notificationStorage.removeRows(rows.map(_.index))
            _ <- processDecryptedRows()
          } yield {}
        else Future.successful(())
      }
    }

    for {
      _ <- processEncryptedRows()
      _ <- processDecryptedRows()
    } yield {}
  }

  wsPushService.notifications() { notifications =>
    for {
      _ <- fetchInProgress.filter(_ == false).head
      _ <- storeNotifications(notifications)
      _ <- processNotifications() //TODO - can the app die while we're processing? Will it matter?
    } yield {}
  }

  wsPushService.connected().onChanged(_ => sync.syncNotifications(None))

  private def storeNotifications(notifications: Seq[PushNotificationEncoded]): Future[Unit] =
    for {
      _ <- notificationStorage.saveAll(notifications)
      _ <- notifications.lift(notifications.lastIndexWhere(!_.transient)) match {
        case Some(n) => idPref := Some(n.id)
        case None    => Future.successful({})
      }
    } yield {}

  override def onNotificationsResponse(notsPage: Vector[PushNotificationEncoded], trigger: Option[Uid], time: Option[Instant], firstSync: Boolean, historyLost: Boolean) = {
    verbose(s"onNotificationsResponse: nots: ${notsPage.size}, trigger: $trigger, time: $time, firstSync: $firstSync, historyLost: $historyLost")
    def reportMissing(): Future[Unit] =
      for {
        now    <- beDrift.head.map(clock.instant + _) //get time at fetch (before waiting)
        _      <- CancellableFuture.delay(5.seconds).future //wait a few seconds for any lagging FCM notifications before doing comparison
        nw     <- network.networkMode.head
        pushes <- receivedPushes.list()
        inBackground <- lifeCycle.uiActive.map(!_).head
        notsUntilPush = notsPage.takeWhile(n => !trigger.contains(n.id)) ++ notsPage.find(n => trigger.contains(n.id)).toSeq
        missedEvents  = {
          notsUntilPush.filterNot(_.transient).map { n =>
            val eventsToTrack = //Not all event types generate a push notification, here we pull out the most important ones
              JsonDecoder.array(n.events, { case (arr, i) => arr.getJSONObject(i) })
                .filter(ev => TrackingEvents(ev.getString("type")))
                .filter(ev => UserId(ev.getString("from")) != selfUserId)
                .map(_.getString("type"))

            (n.id, eventsToTrack)
          }.filter { case (id, evs) =>
            evs.nonEmpty && !pushes.map(_.id).contains(id)
          }
        }
        _ <-
          if (missedEvents.nonEmpty) { //we didn't get pushes for some returned notifications
            val eventFrequency = TrackingEvents.map(e => (e, missedEvents.toMap.values.flatten.count(_ == e))).toMap
            tracking.track(MissedPushEvent(now, missedEvents.size, inBackground, nw, network.getNetworkOperatorName, eventFrequency, missedEvents.last._1.str))
          } else Future.successful({})

        _ <- receivedPushes.removeAll(notsUntilPush.map(_.id)) //remove all notifications up to the point we've checked so far - leave the rest in case of pagination of notifications
      } yield {}

    beDriftPref.mutate(v => time.map(clock.instant.until(_)).getOrElse(v)).flatMap { _ =>
      if (firstSync) idPref := notsPage.headOption.map(_.id)
      else {
        for {
          _ <- if (historyLost) sync.performFullSync().map(_ => onHistoryLost ! clock.instant()) else Future.successful({})
          _ <- reportMissing()
          _ <- storeNotifications(notsPage)
          _ <- sync.processNotifications()
        } yield {}
      }
    }
  }
}

object PushNotificationService {

  //These are the most important event types that generate push notifications
  val TrackingEvents = Set("conversation.otr-message-add", "conversation.create", "conversation.rename", "conversation.member-join")
}
