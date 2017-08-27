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
package com.waz.service.otr

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.Verification
import com.waz.api.impl.ErrorResponse
import com.waz.content.UserPreferences.LastSelfClientsSyncRequestedTime
import com.waz.content._
import com.waz.model.otr.{Client, ClientId, UserClients}
import com.waz.model.{OtrClientAddEvent, OtrClientEvent, OtrClientRemoveEvent, UserId}
import com.waz.service._
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.OtrClient
import com.waz.utils._
import com.waz.utils.events.Signal

import scala.collection.immutable.Map
import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.duration._

class OtrClientsService(userId: UserId, clientId: Signal[Option[ClientId]], netClient: OtrClient, userPrefs: UserPreferences, storage: OtrClientsStorage, sync: SyncServiceHandle, lifecycle: ZmsLifeCycle, updater: VerificationStateUpdater) {

  import com.waz.threading.Threading.Implicits.Background
  import com.waz.utils.events.EventContext.Implicits.global

  private lazy val lastSelfClientsSyncPref = userPrefs.preference(LastSelfClientsSyncRequestedTime)

  lifecycle.loggedIn {
    case true => requestSyncIfNeeded()
    case false =>
  }

  val otrClientsProcessingStage = EventScheduler.Stage[OtrClientEvent] { (convId, events) =>
    RichFuture.processSequential(events) {
      case OtrClientAddEvent(client) =>
        updateClients(Map(userId -> Seq(client))).flatMap(_ => sync.syncPreKeys(userId, Set(client.id)))
      case OtrClientRemoveEvent(cId) =>
        removeClients(userId, Seq(cId))
    }
  }

  def requestSyncIfNeeded(retryInterval: FiniteDuration = 7.days) =
    getSelfClient.zip(lastSelfClientsSyncPref()) flatMap {
      case (Some(c), t) if t > System.currentTimeMillis() - retryInterval.toMillis => Future.successful(())
      case _ =>
        sync.syncSelfClients() flatMap { _ =>
          lastSelfClientsSyncPref := System.currentTimeMillis()
        }
    }

  def deleteClient(id: ClientId, password: String) =
    storage.get(userId) flatMap {
      case Some(cs) if cs.clients.contains(id) =>
        netClient.deleteClient(id, password).future flatMap {
          case Right(_) => for {
            _ <- storage.update(userId, { uc => uc.copy(clients = uc.clients - id) })
            _ <- requestSyncIfNeeded()
          } yield Right(())
          case res => Future.successful(res)
        }
      case _ => Future.successful(Left(ErrorResponse.internalError("Client does not belong to current user or was already deleted")))
    }

  def getClient(id: UserId, client: ClientId) = storage.get(id) map { _.flatMap(_.clients.get(client)) }

  def getOrCreateClient(id: UserId, client: ClientId) = {
    storage.get(id) flatMap {
      case Some(uc) if uc.clients.contains(client) => Future.successful(uc.clients(client))
      case _ =>
        def create = UserClients(id, Map(client -> Client(client, "")))
        def update(uc: UserClients) = uc.copy(clients = uc.clients.updated(client, uc.clients.getOrElse(client, Client(client, ""))))
        storage.updateOrCreate(id, update, create) flatMap { ucs =>
          val res = ucs.clients(client)
          if (res.isVerified) Future successful res
          else VerificationStateUpdater.awaitUpdated(userId) map { _ => res } // synchronize with verification state processing to ensure that OTR_UNVERIFIED message is added before anything else
        }
    }
  }

  def updateClients(ucs: Map[UserId, Seq[Client]], replace: Boolean = false): Future[Set[UserClients]] = {

    def updateOrCreate(user: UserId, clients: Seq[Client]): (Option[UserClients] => UserClients) = {
      case Some(cs) =>
        val prev = cs.clients
        val updated: Map[ClientId, Client] = clients.map { c => c.id -> prev.get(c.id).fold(c)(_.updated(c)) }(breakOut)
        cs.copy(clients = if (replace) updated else prev ++ updated)
      case None =>
        UserClients(user, clients.map(c => c.id -> c)(breakOut))
    }

    // request clients location sync if some location has no name
    // location will be present only for self clients, but let's check that just to be explicit
    def requestLocationSyncIfNeeded(uss: Traversable[UserClients]) = {
      val needsSync = uss.filter(_.clients.values.exists(_.regLocation.exists(!_.hasName)))
      if (needsSync.nonEmpty)
        if (needsSync.exists(_.user == userId)) sync.syncClientsLocation() else Future.successful(())
    }

    storage.updateOrCreateAll(ucs.map { case (u, cs) => u -> updateOrCreate(u, cs) } (breakOut)) map { res =>
      requestLocationSyncIfNeeded(res)
      res
    }
  }

  def updateUserClients(user: UserId, clients: Seq[Client], replace: Boolean = false) = {
    verbose(s"updateUserClients($user, $clients, $replace)")
    updateClients(Map(user -> clients), replace).map(_.head)
  }

  def onCurrentClientRemoved() = clientId.head flatMap {
    case Some(id) => storage.update(userId, _ - id)
    case None => Future successful None
  }

  def removeClients(user: UserId, clients: Seq[ClientId]) =
    storage.update(user, { cs =>
      cs.copy(clients = cs.clients -- clients)
    })

  def updateSelfClients(clients: Seq[Client], replace: Boolean = true) = clientId.head flatMap { current =>
    updateUserClients(userId, clients.map(c => if (current.contains(c.id)) c.copy(verified = Verification.VERIFIED) else c), replace)
  }

  def updateClientLabel(id: ClientId, label: String) =
    storage.update(userId, { cs =>
      cs.clients.get(id).fold(cs) { client =>
        cs.copy(clients = cs.clients.updated(id, client.copy(label = label)))
      }
    }) flatMap {
      case Some(_) =>
        verbose(s"clientLabel updated, client: $id, label: $label")
        sync.postClientLabel(id, label)
      case None =>
        verbose(s"client label was not updated ($id, $label)")
        Future.successful(())
    }

  def selfClient = for {
    uc <- storage.signal(userId)
    cId <- clientId
    res <- cId.flatMap(uc.clients.get).fold(Signal.empty[Client])(Signal.const)
  } yield res

  def getSelfClient: Future[Option[Client]] =
    storage.get(userId).zip(clientId.head) map {
      case (Some(cs), Some(id)) =>
        verbose(s"self clients: $cs, clientId: $id")
        cs.clients.get(id)
      case _ => None
    }

  def updateUnknownToUnverified(userId: UserId): Future[Unit] =
    storage.update(userId, { uc =>
      uc.copy(clients = uc.clients.map{ client =>
        if (client._2.verified == Verification.UNKNOWN)
          (client._1, client._2.copy(verified = Verification.UNVERIFIED))
        else
          client
      })
    }).map(_ => ())
}
