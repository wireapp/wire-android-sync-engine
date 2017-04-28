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
package com.waz.api.impl.otr

import java.util.Locale

import android.os.Parcel
import com.waz.ZLog._
import com.waz.api
import com.waz.api.OtrClient.{DeleteCallback, ResetCallback}
import com.waz.api.impl.{CoreList, ErrorResponse, UiObservable, UiSignal}
import com.waz.api.{Location, OtrClientType, Verification, OtrClient => ApiClient}
import com.waz.model.otr.{Client, ClientId}
import com.waz.model.{AccountData, ConvId, UserId, otr}
import com.waz.service.AccountService
import com.waz.service.otr.OtrService
import com.waz.sync.SyncResult
import com.waz.threading.Threading
import com.waz.ui.{SignalLoading, UiModule}
import com.waz.utils._
import com.waz.utils.events.Signal
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future

class OtrClients(signal: AccountService => Signal[(UserId, Vector[Client])])(implicit ui: UiModule) extends CoreList[ApiClient] with SignalLoading {
  import OtrClients._
  private var items = IndexedSeq.empty[OtrClient]

  accountLoader(signal) { case (user, clients) =>
    verbose(s"clients: $clients")
    val recycled: Map[ClientId, OtrClient] = items.map(c => c.clientId -> c) (breakOut)
    items = clients .map { c => recycled.getOrElse(c.id, new OtrClient(user, c.id, c)).update(c) } (breakOut)
    notifyChanged()
  }

  override def get(position: Int) = items(position)

  override def size() = items.size
}

object OtrClients {
  import Threading.Implicits.Background
  private implicit val tag: LogTag = logTagFor[OtrClients]

  // ascending by model and desc by time
  // clients for other users will always have model and time empty, in that case we sort by id
  val ClientOrdering: Ordering[Client] = Ordering.by { client: Client =>
    (client.model, -client.regTime.fold(0L)(_.toEpochMilli), client.id.str)
  }

  val IncomingClientOrdering: Ordering[Client] = Ordering.by { client: Client =>
    (-client.regTime.fold(0L)(_.toEpochMilli), client.model, client.id.str)
  }

  val Empty = new CoreList[ApiClient] {
    override def get(position: Int): ApiClient = null
    override def size(): Int = 0
  }

  def apply(user: UserId, skipSelf: Boolean = false)(implicit ui: UiModule): OtrClients = new OtrClients({ account =>
    // request refresh of clients list, this will be executed only when UI goes to devices list,
    // so should be safe to schedule sync every time
    account.getZMessaging.flatMap {
      case Some(zms) => zms.sync.syncClients(user)
      case None => Future.successful(())
    }

    for {
      clients <- account.storage.otrClientsStorage.signal(user)
      acc     <- account.accountData
    } yield {
      val cs = clients.clients.values.toVector.sorted(ClientOrdering)
      (user, if (skipSelf) cs.filter(c => !acc.clientId.contains(c.id)) else cs)
    }
  })

  def incoming(implicit ui: UiModule): OtrClients = new OtrClients({ account =>

    account.accountData flatMap {
      case AccountData(_, _, _, _, _, _, _, _, _, _, Some(userId), Some(clientId), _, _) =>
        account.storage.otrClientsStorage.incomingClientsSignal(userId, clientId).map { cs =>
          (userId, cs.sorted(IncomingClientOrdering).toVector)
        }
      case _ => Signal.empty
    }
  })
}

class OtrClient(val userId: UserId, val clientId: ClientId, var data: Client)(implicit ui: UiModule) extends api.OtrClient with UiObservable with SignalLoading {
  import OtrClient._
  import Threading.Implicits.Background

  addLoader(_.storage.otrClientsStorage.signal(userId).map(_.clients.get(clientId))) { _ foreach update }

  def update(data: Client) = {
    if (this.data != data) {
      this.data = data
      notifyChanged()
    }
    this
  }

  override def getId: String =  data.id.str

  override def getDisplayId: String = f"${data.id.str.toUpperCase(Locale.ENGLISH)}%16s" replace (' ', '0') grouped 4 map { group =>
    val (bold, normal) = group.splitAt(2)
    s"[[$bold]] $normal"
  } mkString " "

  override def getLabel = data.label

  override def getModel = data.model

  override def getRegTime: Instant = data.regTime.orNull

  override def getRegIpAddress: String = data.regIpAddress.getOrElse("")

  override def getRegLocation: Location = {
    val loc = data.regLocation.getOrElse(otr.Location.Empty)
    new Location {
      override def getLongitude = loc.lon
      override def getLatitude = loc.lat
      override def getDisplayName = loc.name
    }
  }

  override def getType: OtrClientType = data.devType

  override def getFingerprint: api.UiSignal[Fingerprint] = {
    def signal(acc: AccountService) = acc.accountData flatMap { account =>
      if (account.userId.contains(userId) && account.clientId.contains(clientId)) Signal.future(acc.cryptoBox { cb => Future successful cb.getLocalFingerprint })
      else acc.cryptoBox.sessions.remoteFingerprint(OtrService.sessionId(userId, clientId))
    }

    UiSignal.accountMapped(signal(_: AccountService).collect { case Some(bytes) => bytes }, new Fingerprint(_: Array[Byte]))
  }

  // was this client fingerprint verified (manually or by sync from trusted device)
  override def getVerified: Verification = data.verified

  override def setVerified(trusted: Boolean): Unit = ui.getAccount.flatMap(_.storage.otrClientsStorage.updateVerified(userId, clientId, trusted))

  override def delete(password: String, cb: DeleteCallback): Unit =
    ui.getUserModule.flatMap(_.clientsService.deleteClient(clientId, password)) .map {
      case Right(_) => cb.onClientDeleted(this)
      case Left(ErrorResponse(_, msg, _)) => cb.onDeleteFailed(msg)
    } (Threading.Ui)

  override def resetSession(callback: ResetCallback): Unit = {
    ui.zms.flatMapFuture { zms =>
      // reset session msg has to be sent in some conv, will try to do it in current conv and fallback to 1-1 with this user
      zms.convsStats.selectedConvIdPref() flatMap { conv =>
        zms.otrService.resetSession(conv.getOrElse(ConvId(userId.str)), userId, clientId) flatMap zms.syncRequests.scheduler.await
      }
    }.recover {
      case e: Throwable => SyncResult.failed()
    }.map {
      case SyncResult.Success => callback.onSessionReset(this)
      case SyncResult.Failure(Some(err @ ErrorResponse(code, msg, label)), _) =>
        warn(s"session reset failed: $err")
        callback.onSessionResetFailed(code, msg, label)
      case SyncResult.Failure(_, _) =>
        warn(s"session reset failed")
        callback.onSessionResetFailed(0, "", "")
    }(Threading.Ui)
  }

  override def setLabel(label: String): Unit = {
    // this method will actually change a label only for own clients
    data = data.copy(label = label)
    ui.zms.flatMapFuture(_.otrClientsService.updateClientLabel(clientId, label)).recoverWithLog(reportHockey = true)("OtrClients")
  }

  override def equals(o: scala.Any): Boolean = o match {
    case c: OtrClient => c.userId == userId && c.clientId == clientId
    case _ => false
  }

  override def hashCode(): Int = (userId, clientId).hashCode()

  override def writeToParcel(parcel: Parcel, i: Int): Unit =
    parcel.writeString(JsonEncoder { o =>
      o.put("userId", userId.str)
      o.put("data", JsonEncoder.encode(data))
    } .toString)

  override def describeContents(): Int = 0
}

object OtrClient {
  private implicit val tag: LogTag = logTagFor[OtrClient]

  def fromParcel(p: Parcel)(implicit ui: UiModule): OtrClient = {
    import JsonDecoder._
    implicit val js = new JSONObject(p.readString())
    val userId = UserId('userId)
    val data = JsonDecoder[Client]('data)
    new OtrClient(userId, data.id, data)
  }
}

class Fingerprint(bytes: Array[Byte])(implicit ui: UiModule) extends api.Fingerprint {
  override def getRawBytes: Array[Byte] = bytes
}
