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
package com.waz.sync.otr

import android.content.Context
import android.location.Geocoder
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.ErrorResponse
import com.waz.api.{Verification, ZmsVersion}
import com.waz.content.UserPreferences.ClientRegVersion
import com.waz.content.{OtrClientsStorage, UserPreferences}
import com.waz.model.otr.{Client, ClientId, Location, SignalingKey, UserClients}
import com.waz.model.{AccountId, UserId}
import com.waz.service.AccountManager.ClientRegistrationState
import com.waz.service.AccountManager.ClientRegistrationState.{LimitReached, PasswordMissing, Registered}
import com.waz.service.otr._
import com.waz.sync.SyncResult
import com.waz.sync.client.OtrClient
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.utils.{Locales, LoggedTry, Serialized}
import com.waz.znet.Response.Status

import scala.collection.breakOut
import scala.concurrent.Future

class OtrClientsSyncHandler(context: Context, accountId: AccountId, userId: UserId, clientId: Signal[Option[ClientId]], netClient: OtrClient, otrClients: OtrClientsService, storage: OtrClientsStorage, cryptoBox: CryptoBoxService, userPrefs: UserPreferences) {
  import com.waz.threading.Threading.Implicits.Background

  lazy val sessions = cryptoBox.sessions

  lazy val geocoder = new Geocoder(context, Locales.currentLocale)

  def syncSelfClients(): Future[SyncResult] = Serialized.future("sync-self-clients", this) { // serialized to avoid races with registration
    verbose(s"syncSelfClients")
    syncClients(userId)
  }

  // keeps ZMS_MAJOR_VERSION number of client registration
  // this can be used to detect problematic version updates
  lazy val clientRegVersion = userPrefs.preference(ClientRegVersion)

  def registerClient(password: Option[String]): Future[Either[ErrorResponse, ClientRegistrationState]] = Serialized.future("sync-self-clients", this) {
    cryptoBox.createClient() flatMap {
      case None => Future successful Left(ErrorResponse.internalError("CryptoBox missing"))
      case Some((c, lastKey, keys)) =>
        netClient.postClient(accountId, c, lastKey, keys, password).future flatMap {
          case Right(cl) =>
            for {
              _ <- clientRegVersion := ZmsVersion.ZMS_MAJOR_VERSION
              _ <- otrClients.updateUserClients(userId, Seq(c.copy(id = cl.id).updated(cl)))
            } yield Right(Registered(cl.id))
          case Left(error@ErrorResponse(Status.Forbidden, _, "missing-auth")) =>
            warn(s"client registration not allowed: $error, password missing")
            Future successful Right(PasswordMissing)
          case Left(error@ErrorResponse(Status.Forbidden, _, "too-many-clients")) =>
            warn(s"client registration not allowed: $error")
            Future successful Right(LimitReached)
          case Left(error) =>
            Future.successful(Left(error))
        }
    }
  }

  def syncClients(user: UserId): Future[SyncResult] = clientId.head flatMap { current =>
    verbose(s"syncClients")

    def hasSession(user: UserId, client: ClientId) = sessions.getSession(OtrService.sessionId(user, client)).map(_.isDefined)

    def loadClients = (if (user == userId) netClient.loadClients() else netClient.loadClients(user)).future

    def withoutSession(clients: Iterable[ClientId]) =
      Future.traverse(clients) { client =>
        if (current.contains(client)) Future successful None
        else hasSession(user, client) map { if (_) None else Some(client) }
      } map { _.flatten.toSeq }

    def syncSessionsIfNeeded(clients: Iterable[ClientId]) =
      for {
        toSync <- withoutSession(clients)
        err <- if (toSync.isEmpty) Future successful None else syncSessions(Map(user -> toSync))
      } yield
        err.fold[SyncResult](SyncResult.Success)(SyncResult(_))

    def updatePreKeys(id: ClientId) =
      netClient.loadRemainingPreKeys(id).future flatMap {
        case Right(ids) =>
          verbose(s"remaining prekeys: $ids")
          cryptoBox.generatePreKeysIfNeeded(ids) flatMap {
            case keys if keys.isEmpty => Future.successful(SyncResult.Success)
            case keys => netClient.updateKeys(id, Some(keys)).future map {
              case Right(_) => SyncResult.Success
              case Left(error) => SyncResult(error)
            }
          }
        case Left(error) => Future.successful(SyncResult(error))
      }

    loadClients flatMap {
      case Left(error) => Future successful SyncResult(error)
      case Right(clients) =>

        val userClients =
          if (user == userId)
            clients.map(c => if (current.contains(c.id)) c.copy(verified = Verification.VERIFIED) else c)
          else
            clients

        otrClients.updateUserClients(user, userClients, replace = true) flatMap { ucs =>
          syncSessionsIfNeeded(ucs.clients.keys).flatMap { _ =>
            current match {
              case Some(currentClient) => updatePreKeys(currentClient)
              case _ => Future.successful(SyncResult.Success)
            }
          }
        }
    }
  }

  def postLabel(id: ClientId, label: String): Future[SyncResult] =
    netClient.postClientLabel(id, label).future map {
      case Right(_) => SyncResult.Success
      case Left(err) => SyncResult(err)
    }

  def syncPreKeys(clients: Map[UserId, Seq[ClientId]]): Future[SyncResult] = syncSessions(clients) map {
    case Some(error) => SyncResult(error)
    case None => SyncResult.Success
  }

  def registerSignalingKey(): Future[SyncResult] = {
    clientId.head.flatMap(_.fold(Future.successful(Option.empty[Client]))(otrClients.getClient(userId, _))).flatMap {
      case Some(client) =>
        val sk = Some(SignalingKey())
        netClient.updateKeys(client.id, sigKey = sk).future flatMap {
          case Right(_)  => otrClients.updateClients(Map(userId -> Seq(client.copy(signalingKey = sk)))).map(_ => SyncResult.Success)
          case Left(err) => Future.successful(SyncResult.Failure(Some(err)))
        }
      case _ => Future.successful(SyncResult.Failure(None, shouldRetry = false))
    }
  }

  private[otr] def syncSessions(clients: Map[UserId, Seq[ClientId]]): Future[Option[ErrorResponse]] =
    netClient.loadPreKeys(clients).future
      .flatMap {
        case Left(error) => Future.successful(Some(error))
        case Right(us) =>
          for {
            _ <- otrClients.updateClients(us.mapValues(_.map { case (id, key) => Client(id, "") }))
            prekeys = us.flatMap { case (u, cs) => cs map { case (c, p) => (OtrService.sessionId(u, c), p)} }
            _ <- Future.traverse(prekeys) { case (id, p) => sessions.getOrCreateSession(id, p) }
            _ <- VerificationStateUpdater.awaitUpdated(userId)
          } yield None
      }
      .recover {
        case e: Throwable => Some(ErrorResponse.internalError(e.getMessage))
      }

  def syncClientsLocation(): Future[SyncResult] = {
    import scala.collection.JavaConverters._

    def loadName(lat: Double, lon: Double) = Future {
      LoggedTry.local(geocoder.getFromLocation(lat, lon, 1).asScala).toOption.flatMap(_.headOption).flatMap { add =>
        Option(Seq(Option(add.getLocality), Option(add.getCountryCode)).flatten.mkString(", ")).filter(_.nonEmpty)
      }
    } (Threading.BlockingIO)

    def loadNames(locs: Iterable[Location]) =
      Future.traverse(locs) { l => loadName(l.lat, l.lon).map { (l.lat, l.lon) -> _ } }

    def updateClients(locs: Map[(Double, Double), String])(ucs: UserClients) =
      ucs.copy(clients = ucs.clients.mapValues { c =>
        c.regLocation.flatMap { l =>
          locs.get((l.lat, l.lon)).map(n => l.copy(name = n))
        }.fold(c) { loc => c.copy(regLocation = Some(loc)) }
      })

    storage.get(userId) flatMap {
      case None => Future successful SyncResult.Success
      case Some(ucs) =>
        val toSync = ucs.clients.values collect {
          case Client(_, _, _, _, Some(loc), _, _, _, _) if !loc.hasName => loc
        }
        if (toSync.isEmpty) Future successful SyncResult.Success
        else
          for {
            ls <- loadNames(toSync)
            locations: Map[(Double, Double), String] = ls.collect { case (k, Some(name)) => k -> name }(breakOut)
            update <- storage.update(userId, updateClients(locations))
          } yield {
            update match {
              case Some((_, UserClients(_, cs))) if cs.values.forall(_.regLocation.forall(_.hasName)) => SyncResult.Success
              case _ =>
                verbose(s"user clients were not updated, locations: $locations, toSync: $toSync")
                SyncResult.failed()
            }
          }
    }
  }
}

