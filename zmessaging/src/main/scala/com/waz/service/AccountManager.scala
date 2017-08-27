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
package com.waz.service

import com.softwaremill.macwire._
import com.waz.HockeyApp
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.ClientRegistrationState
import com.waz.api.impl._
import com.waz.model.otr.{Client, ClientId}
import com.waz.model.{UserData, _}
import com.waz.service.otr.OtrService.sessionId
import com.waz.service.otr.{OtrClientsService, VerificationStateUpdater}
import com.waz.sync._
import com.waz.sync.client.OtrClient
import com.waz.sync.otr.OtrClientsSyncHandler
import com.waz.sync.queue.{SyncContentUpdater, SyncContentUpdaterImpl}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.wrappers.Context
import com.waz.utils.{RichInstant, _}
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient._
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Right
import scala.util.control.NonFatal

//TODO consider merging with AccountManager
class UserModule(val userId: UserId, val account: AccountManager) {
  private implicit val dispatcher = account.dispatcher

  def context = account.global.context
  def contextWrapper = Context.wrap(context)
  def db = account.storage.db
  def clientId = account.clientId
  def accountId = account.id
  def timeouts = account.global.timeouts
  def network = account.global.network
  def userPrefs = account.storage.userPrefs
  def usersStorage = account.storage.usersStorage
  def accStorage   = account.global.accountsStorage
  def convsStorage = account.storage.convsStorage
  def membersStorage = account.storage.membersStorage
  def clientsStorage = account.storage.otrClientsStorage
  def lifecycle = account.global.lifecycle
  def cryptoBox = account.cryptoBox
  def reporting = account.global.reporting

  lazy val otrClient = new OtrClient(account.netClient)

  lazy val verificationUpdater                          = wire[VerificationStateUpdater]
  lazy val clientsService:      OtrClientsService       = wire[OtrClientsService]
  lazy val clientsSync:         OtrClientsSyncHandler   = wire[OtrClientsSyncHandler]
  lazy val syncContent:         SyncContentUpdater      = wire[SyncContentUpdaterImpl]
  lazy val syncRequests:        SyncRequestServiceImpl  = wire[SyncRequestServiceImpl]
  lazy val sync:                SyncServiceHandle       = wire[AndroidSyncServiceHandle]
  lazy val syncHandler:         SyncHandler             = new AccountSyncHandler(account.zmessaging.collect { case Some(zms) => zms }, clientsSync)

  def ensureClientRegistered(accountId: AccountId): ErrorOr[Unit] = {
    verbose(s"ensureClientRegistered: $account")
    accStorage.get(accountId).flatMap {
      case Some(account) =>
        if (account.clientId.isDefined) Future.successful(Right({}))
        else {
          import com.waz.api.ClientRegistrationState._
          clientsSync.registerClient(account.password) flatMap {
            case Right((state, cl)) =>
              verbose(s"Client registration complete: $state, $cl")
              if (state != REGISTERED || cl.isEmpty) {
                sync.syncSelfClients()
              }
              accStorage.update(account.id, acc => acc.copy(clientId = cl.map(_.id), clientRegState = state)).map(_ => Right({}))
            case Left(err) =>
              error(s"client registration failed: $err")
              Future.successful(Left(err))
          }
        }
      case _ => Future.successful(Left(ErrorResponse.InternalError))
    }
  }
}

class AccountManager(val id: AccountId, val global: GlobalModule, accounts: AccountsService)(implicit ec: EventContext) { self =>
  import AccountManager._
  implicit val dispatcher = new SerialDispatchQueue()
  verbose(s"Creating for: $id")

  import global._
  val storage: StorageModule = global.factory.baseStorage(id)

  accountData.onChanged { acc =>
    acc.userId.foreach { uId =>
      storage.usersStorage.updateOrCreate(uId, identity, UserData(uId, None, "", acc.email, acc.phone, searchKey = SearchKey(""), connection = UserData.ConnectionStatus.Self, handle = acc.handle))
    }
  }

  private val otrClients = accountData.map(_.userId).flatMap {
    case Some(uid) => storage.otrClientsStorage.signal(uid).map(_.clients.values.toSet).orElse(Signal.const(Set.empty[Client]))
    case _ => Signal.const(Set.empty[Client])
  }

  // listen to client changes, logout and delete cryptobox if current client is removed
  private val otrCurrentClient = accountData.map(_.clientId).flatMap {
    case Some(cId) => otrClients.map(_.find(_.id == cId))
    case _ => Signal const Option.empty[Client]
  }

  private var hasClient = false
  otrCurrentClient.map(_.isDefined) { exists =>
    if (hasClient && !exists) {
      info(s"client has been removed on backend, logging out")
      logoutAndResetClient()
    }
    hasClient = exists
  }

  private val selfUserData = accountData.map(_.userId).flatMap {
    case Some(uId) => storage.usersStorage.optSignal(uId)
    case None         => Signal const Option.empty[UserData]
  }

  // listen to user data changes, update account email/phone if self user data is changed
  selfUserData.collect {
    case Some(user) => (user.email, user.phone)
  } { case (email, phone) =>
    verbose(s"self user data changed, email: $email, phone: $phone")
    accountsStorage.update(id, _.copy(email = email, phone = phone))
  }

  selfUserData.map(_.exists(_.deleted)) { deleted =>
    if (deleted) {
      info(s"self user was deleted, logging out")
      for {
        _ <- logoutAndResetClient()
        _ =  accounts.accountMap.remove(id)
        _ <- accountsStorage.remove(id)
      // TODO: delete database, account was deleted
      } yield ()
    }
  }

  lazy val cryptoBox          = global.factory.cryptobox(id, storage)
  lazy val auth               = global.factory.auth(id)
  lazy val netClient          = global.factory.client(id, auth)
  lazy val usersClient        = global.factory.usersClient(netClient)
  lazy val teamsClient        = global.factory.teamsClient(netClient)
  lazy val credentialsClient  = global.factory.credentialsClient(netClient)

  auth.onInvalidCredentials.on(dispatcher)(_ => logout(flushCredentials = true))

  @volatile private var _userModule = Option.empty[UserModule]

  lazy val userModule = userId collect {
    case Some(user) =>
      _userModule = Some(_userModule.getOrElse(global.factory.userModule(user, this)))
      _userModule.get
  }

  lazy val accountData = accountsStorage.signal(id)

  lazy val clientId = accountData.map(_.clientId)

  lazy val userId = accountData.map(_.userId)

  // logged in zmessaging instance
  @volatile private var _zmessaging = Option.empty[ZMessaging]

  val zmessaging = (for {
    Some(cId)  <- clientId
    aId        <- accountData.map(_.id)
    _          <- Signal.future(updateSelfTeam(aId))
    Right(tId) <- accountData.map(_.teamId)
    um         <- userModule
    Some(_)    <- Signal.future { cryptoBox.cryptoBox }
  } yield {
    verbose(s"Creating new ZMessaging instance, for $um, $cId, $tId, service: $this")
    _zmessaging = _zmessaging orElse LoggedTry(global.factory.zmessaging(tId, cId, um)).toOption
    _zmessaging
  }).orElse(Signal const Option.empty[ZMessaging])

  for {
    acc      <- accountData if acc.verified
    loggedIn <- global.lifecycle.accLoggedIn(id)
    client   <- otrCurrentClient
    _        <- otrClients.map(_.size)
  } {
    if (acc.userId.isEmpty || acc.clientId.isEmpty) {
      verbose(s"account data needs registration: $acc")
      Serialized.future(self)(ensureFullyRegistered())
    }

    client.foreach { client =>
      if (client.signalingKey.isEmpty) {
        returning (s"Client registered ${client.regTime.map(_ until Instant.now).map(_.toDays).getOrElse(0)} ago is missing its signaling key") { msg =>
          warn(msg)
          HockeyApp.saveException(new IllegalStateException(msg), msg)
        }
        Serialized.future(self)(userModule.head.map(_.clientsSync.registerSignalingKey()))
      }
    }
  }

  private var awaitActivationFuture = CancellableFuture successful Option.empty[AccountData]

  private val shouldAwaitActivation = lifecycle.accInForeground(id).zip(accountsStorage.optSignal(id)) map {
    case (true, Some(acc)) => !acc.verified && acc.password.isDefined && (acc.pendingPhone.isDefined || acc.pendingEmail.isDefined)
    case _ => false
  }

  shouldAwaitActivation.on(dispatcher) {
    case true   => awaitActivationFuture = awaitActivationFuture.recover { case _: Throwable => () } flatMap { _ => awaitActivation(0) }
    case false  => awaitActivationFuture.cancel()("stop_await_activate")
  }

  def logout(flushCredentials: Boolean): Future[Unit] = {
    verbose(s"logout($id, flushCredentials: $flushCredentials)")
    accounts.logout(id, flushCredentials)
  }

  def getZMessaging: Future[Option[ZMessaging]] = zmessaging.head flatMap {
    case Some(zms) => Future successful Some(zms)
    case None =>
      Serialized.future(this) {
        accountsStorage.get(id) flatMap {
          case Some(acc) if acc.cookie.isEmpty =>
            verbose(s"account data has no cookie, user not logged in: $acc")
            Future successful None
          case Some(_) =>
            ensureFullyRegistered() flatMap {
              case Right(a) if a.userId.isDefined && a.clientId.isDefined && a.verified =>
                zmessaging.filter(_.isDefined).head // wait until loaded
              case _ =>
                zmessaging.head
            }
          case None =>
            Future successful None
        }
      }
  }

  def updateEmail(email: EmailAddress): ErrorOrResponse[Unit] = credentialsClient.updateEmail(email)

  def clearEmail(): ErrorOr[Unit] =
    credentialsClient.clearEmail().future.flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_)  => updateSelfAccountAndUser(_.copy(email = None), _.copy(email = None)).map(_ => Right({}))

    }

  def updatePhone(phone: PhoneNumber): ErrorOrResponse[Unit] = credentialsClient.updatePhone(phone)

  def clearPhone(): ErrorOr[Unit] =
    credentialsClient.clearPhone().future.flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_)  => updateSelfAccountAndUser(_.copy(phone = None), _.copy(phone = None)).map(_ => Right({}))
    }

  def updatePassword(newPassword: String, currentPassword: Option[String]) =
    credentialsClient.updatePassword(newPassword, currentPassword).future flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_) =>
        accountsStorage.update(id, _.copy(password = Some(newPassword))) flatMap { _ =>
          getZMessaging map { _ => Right(()) }
        }
    }

  def updateHandle(handle: Handle): ErrorOr[Unit] =
    credentialsClient.updateHandle(handle).future.flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_)  => updateSelfAccountAndUser(_.copy(handle = Some(handle)), _.copy(handle = Some(handle))).map(_ => Right({}))
    }

  def fingerprintSignal(uId: UserId, cId: ClientId): Signal[Option[Array[Byte]]] =
    for {
      selfId       <- userId
      selfClientId <- clientId
      fingerprint  <-
        if (selfId.contains(uId) && selfClientId.contains(cId))
          Signal.future(cryptoBox { cb => Future successful cb.getLocalFingerprint })
        else
          cryptoBox.sessions.remoteFingerprint(sessionId(uId, cId))
    } yield fingerprint

  private def updateSelfAccountAndUser(acc: AccountData => AccountData, user: UserData => UserData) = {
    for {
      _         <- accountsStorage.update(id, acc)
      Some(zms) <- getZMessaging
      _         <- zms.usersStorage.update(zms.selfUserId, user)
    } yield {}
  }

  private def updateSelfTeam(accountId: AccountId): ErrorOr[Unit] = accountsStorage.get(accountId).flatMap {
    case Some(account) => account.teamId match {
      case Right(_) => Future.successful(Right({}))
      case Left(_) => teamsClient.findSelfTeam().future flatMap {
        case Right(teamOpt) =>
          val updateUsers = (teamOpt, account.userId) match {
            case (Some(t), Some(uId)) => storage.usersStorage.update(uId, _.updated(Some(t.id))).map(_ => {})
            case _ => Future.successful({})
          }

          val updateTeams = teamOpt match {
            case Some(t) => teamsStorage.updateOrCreate(t.id, _ => t, t)
            case _ => Future.successful({})
          }

          val fetchPermissions = (teamOpt, account.userId) match {
            case (Some(t), Some(u)) => teamsClient.getPermissions(t.id, u).map {
              case Right(p) => Some(p)
              case Left(_) => None
            }.future
            case _ => Future.successful(None)
          }

          for {
            _ <- updateUsers
            _ <- updateTeams
            p <- fetchPermissions
            _ <- accountsStorage.update(id, _.withTeam(teamOpt.map(_.id), p))
          } yield Right({})
        case Left(err) => Future.successful(Left(err))
      }
    }
    case _ => Future.successful(Left(ErrorResponse.InternalError))
  }


  def ensureFullyRegistered(): Future[Either[ErrorResponse, AccountData]] = {
    verbose(s"ensureFullyRegistered()")

    def updateSelfUser(accountId: AccountId): ErrorOr[Unit] =
      accountsStorage.get(accountId).flatMap { account =>
        if (account.exists(_.userId.isDefined)) Future.successful(Right({}))
        else {
          usersClient.loadSelf().future flatMap {
            case Right(userInfo) =>
              verbose(s"got self user info: $userInfo")
              for {
                _ <- storage.assetsStorage.mergeOrCreateAsset(userInfo.mediumPicture)
                _ <- storage.usersStorage.updateOrCreate(userInfo.id, _.updated(userInfo).copy(syncTimestamp = System.currentTimeMillis()), UserData(userInfo).copy(connection = UserData.ConnectionStatus.Self, syncTimestamp = System.currentTimeMillis()))
                _ <- accountsStorage.update(id, _.updated(userInfo))
              } yield Right({})
            case Left(err) =>
              verbose(s"loadSelfUser failed: $err")
              Future successful Left(err)
          }
        }
      }

    def checkCryptoBox =
      cryptoBox.cryptoBox flatMap {
        case Some(cb) => Future successful Some(cb)
        case None =>
          _zmessaging = None
          for {
            _ <- accountsStorage.update(id, _.copy(clientId = None, clientRegState = ClientRegistrationState.UNKNOWN))
            _ <- cryptoBox.deleteCryptoBox()
            res <- cryptoBox.cryptoBox
          } yield res
      }

    // TODO: check if there is some other AccountData with the same userId already present
    // it may happen that the same account was previously used with different credentials
    // we should merge those accounts, delete current one, and switch to the previous one
    (for {
      Some(_)      <- checkCryptoBox
      Right(_)     <- activate(id)
      Right(_)     <- updateSelfUser(id)
      Right(_)     <- userModule.head.flatMap(_.ensureClientRegistered(id))
      Right(_)     <- updateSelfTeam(id)
      Some(after)  <- accountsStorage.get(id)
    } yield Right(after))
      .recover {
        case NonFatal(_) =>
          val msg = s"Error during registration for account: $id"
          warn(msg)
          Left(ErrorResponse.internalError(msg))
      }
  }

  private def activate(accountId: AccountId): ErrorOr[AccountData] =
    accountsStorage.get(accountId).flatMap {
      case Some(account) =>
        if (account.verified && !account.regWaiting) Future successful Right(account)
        else loginClient.login(account).future flatMap {
          case Right((token, cookie)) =>
            for {
              Some((_, acc)) <- accountsStorage.update(id, _.updatedNonPending.copy(cookie = cookie, accessToken = Some(token)))
            } yield Right(acc)
          case Left((_, ErrorResponse(Status.Forbidden, _, "pending-activation"))) =>
            accountsStorage.update(accountId, _.updatedPending).collect { case Some((_, acc)) => Left(ErrorResponse(Status.Forbidden, "", "pending-activation"))}
          case Left((_, err)) =>
            verbose(s"activate failed: $err")
            Future.successful(Left(err))
        }
      case None => Future.successful(Left(ErrorResponse.internalError(s"no account for $accountId")))
    }

  private def awaitActivation(retry: Int = 0): CancellableFuture[Option[AccountData]] =
    CancellableFuture lift accountsStorage.get(id) flatMap {
      case None => CancellableFuture successful None
      case Some(data) if data.verified => CancellableFuture successful Some(data)
      case Some(data) =>
        CancellableFuture.lift(lifecycle.accInForeground(id).head).flatMap {
          case true => CancellableFuture.lift(activate(data.id)).flatMap {
            case Right(acc) if acc.verified => CancellableFuture successful Some(acc)
            case _ =>
              CancellableFuture.delay(ActivationThrottling.delay(retry)) flatMap { _ => awaitActivation(retry + 1) }
          }
          case false => CancellableFuture.successful(None)
        }
    }

  private def logoutAndResetClient() =
    for {
      _ <- logout(flushCredentials = true)
      _ <- cryptoBox.deleteCryptoBox()
      _ =  _zmessaging = None // drop zmessaging instance, we need to create fresh one with new clientId // FIXME: dropped instance will still be active and using the same ZmsLifecycle instance
      _ <- accountsStorage.update(id, _.copy(clientId = None, clientRegState = ClientRegistrationState.UNKNOWN))
    } yield ()
}

object AccountManager {
  val ActivationThrottling = new ExponentialBackoff(2.seconds, 15.seconds)
}
