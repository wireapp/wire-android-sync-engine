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

import android.content.Intent
import android.util.Base64
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model.{Uid, UserId}
import com.waz.service.ZMessaging
import com.waz.service.push.GcmGlobalService.PushSenderId
import com.waz.sync.client.PushNotification
import com.waz.threading.Threading
import com.waz.utils.{JsonDecoder, LoggedTry, TimedWakeLock}
import com.waz.zms.GcmHandlerService.Notification.{EncryptedData, LargeNotification, Unencrypted}
import org.json
import org.json.JSONObject

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class GcmHandlerService extends FutureService with ZMessagingService {
  import Threading.Implicits.Background

  lazy val gcm = ZMessaging.currentGlobal.gcmGlobal
  lazy val accounts = ZMessaging.currentAccounts

  override lazy val wakeLock = new TimedWakeLock(getApplicationContext, 2.seconds)

  override protected def onIntent(intent: Intent, id: Int): Future[Any] = wakeLock.async {
    import com.waz.zms.GcmHandlerService._

    def extrasToString = Option(intent.getExtras).map(ex => ex.keySet().asScala.map(k => s"$k -> ${ex.get(k)}").toSeq)

    verbose(s"onIntent with extras: $extrasToString")

    //TODO we no longer rely on the content of GCM notifications - we could get rid of this decryption since we do it later on fetch anyway.
    //The tricky thing is that we rely on the event time (for conv events) in order to determine if we're missing GCM notifications or not...
    def getNotification = Future {
      intent match {
        case FromExtra(from) if from != gcm.gcmSenderId =>
          info(s"received gcm notification is from unexpected sender, will ignore it")
          None
        case ContentAndMac(content, mac) =>
          Some(EncryptedData(content, mac))
        case NotificationForUser(notification, user) =>
          Some(Unencrypted(notification, user))
        case LargeGcmNotification(id) =>
          Some(LargeNotification(id))
        case  _ =>
          error(s"Received GCM notification, but some required extra field is missing, intent extras: $extrasToString")
          None
      }
    }

    def decryptNotification(content: Array[Byte], mac: Array[Byte]) =
      accounts.getCurrentZms flatMap {
        case Some(zms) =>
          zms.otrService.decryptGcm(content, mac) map {
            case Some(EncryptedGcm(notification)) => Some((zms, notification))
            case resp =>
              warn(s"gcm decoding failed: $resp")
              None
          }
        case None =>
          warn("no current zmessaging instance")
          Future successful None
      }

    getNotification flatMap {
      case Some(EncryptedData(content, mac)) =>
        decryptNotification(content, mac) flatMap {
          case Some((zms, notification)) => zms.gcm.addNotificationToProcess(notification.id, Some(notification))
          case None => Future.successful(())
        }
      case Some(Unencrypted(notification, userId)) =>
        accounts.getCurrentZms flatMap {
          case Some(zms) if zms.selfUserId == userId =>
            zms.gcm.addNotificationToProcess(notification.id, Some(notification))
          case Some(zms) =>
            warn(s"received notification for wrong user: $extrasToString")
            Future.successful(())
          case None =>
            Future.successful(())
        }
      case Some(LargeNotification(nId)) =>
        warn("Received large GCM notification - need to trigger fetch")
        accounts.getCurrentZms flatMap {
          case Some(zms) => zms.gcm.addNotificationToProcess(nId)
          case None => Future.successful({})
        }
      case None =>
        Future.successful(())
    }
  }
}

object GcmHandlerService {
  val ContentExtra = "data"
  val UserExtra = "user"
  val TypeExtra = "type"
  val MacExtra = "mac"

  object FromExtra {
    def unapply(intent: Intent): Option[PushSenderId] = Option(intent.getStringExtra("from")) map PushSenderId
  }

  object ContentAndMac {
    def unapply(intent: Intent): Option[(Array[Byte], Array[Byte])] =
      (Option(intent.getStringExtra(TypeExtra)), Option(intent.getStringExtra(ContentExtra)), Option(intent.getStringExtra(MacExtra))) match {
        case (Some("otr" | "cipher"), Some(content), Some(mac)) =>
          LoggedTry.local { (Base64.decode(content, Base64.NO_WRAP | Base64.NO_CLOSE), Base64.decode(mac, Base64.NO_WRAP | Base64.NO_CLOSE)) } .toOption
        case _ => None
      }
  }

  object NotificationForUser {
    def unapply(intent: Intent): Option[(PushNotification, UserId)] =
      (Option(intent.getStringExtra(ContentExtra)), Option(intent.getStringExtra(UserExtra))) match {
        case (Some(content), Some(user)) =>
          LoggedTry.local { (PushNotification.NotificationDecoder(new JSONObject(content)), UserId(user)) } .toOption
        case _ => None
      }
  }

  object LargeGcmNotification {
    def unapply(intent: Intent): Option[Uid] = Option(intent.getStringExtra(ContentExtra)).flatMap(s => Try(JsonDecoder.decodeUid('id)(new json.JSONObject(s))).toOption)
  }

  object EncryptedGcm {
    def unapply(js: JSONObject): Option[PushNotification] = LoggedTry.local { PushNotification.NotificationDecoder(js.getJSONObject("data")) }.toOption
  }

  sealed trait Notification
  object Notification {
    case class EncryptedData(data: Array[Byte], mac: Array[Byte]) extends Notification
    case class Unencrypted(notification: PushNotification, userId: UserId) extends Notification
    case class LargeNotification(id: Uid) extends Notification
  }
}
