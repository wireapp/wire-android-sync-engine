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
import com.waz.api.impl._
import com.waz.content.UserPreferences._
import com.waz.model.otr.{Client, ClientId}
import com.waz.model.{UserData, _}
import com.waz.service.AccountManager.ClientRegistrationState.Unregistered
import com.waz.service.AccountsService.{InForeground, LoggedOut}
import com.waz.service.otr.OtrService.sessionId
import com.waz.service.otr.{OtrClientsService, VerificationStateUpdater}
import com.waz.sync._
import com.waz.sync.client.OtrClient
import com.waz.sync.otr.OtrClientsSyncHandler
import com.waz.sync.queue.{SyncContentUpdater, SyncContentUpdaterImpl}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.events.{EventContext, EventStream, Signal, SourceStream}
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

  lazy val storage = account.storage.currentValue.get
  lazy val cryptoBox = account.cryptoBox.currentValue.get

  def context = account.global.context
  def contextWrapper = Context.wrap(context)
  def db = storage.db
  def clientId = account.clientId
  def accountId = account.id
  def timeouts = account.global.timeouts
  def network = account.global.network
  def userPrefs = storage.userPrefs
  def usersStorage = storage.usersStorage
  def accStorage   = account.global.accountsStorage
  def convsStorage = storage.convsStorage
  def membersStorage = storage.membersStorage
  def clientsStorage = storage.otrClientsStorage
  def lifecycle = account.global.lifecycle
  def reporting = account.global.reporting
  def accountService = account.accounts

  lazy val otrClient = new OtrClient(account.netClient)

  implicit lazy val accountContext: AccountContext      = new AccountContext(accountId, accountService)
  lazy val verificationUpdater                          = wire[VerificationStateUpdater]
  lazy val clientsService:      OtrClientsService       = wire[OtrClientsService]
  lazy val clientsSync:         OtrClientsSyncHandler   = wire[OtrClientsSyncHandler]
  lazy val syncContent:         SyncContentUpdater      = wire[SyncContentUpdaterImpl]
  lazy val syncRequests:        SyncRequestServiceImpl  = wire[SyncRequestServiceImpl]
  lazy val sync:                SyncServiceHandle       = wire[AndroidSyncServiceHandle]
  lazy val syncHandler:         SyncHandler             = new AccountSyncHandler(account.zmessaging.collect { case Some(zms) => zms }, clientsSync)

  def ensureClientRegistered(accountId: AccountId): ErrorOr[Unit] = {
    verbose(s"ensureClientRegistered: $account")
    for {
      acc    <- accStorage.get(accountId)
      client <- clientId.head
      resp   <- acc.fold2(Future.successful(Left(ErrorResponse.InternalError)), acc =>
        if (client.isDefined) Future.successful(Right({}))
        else {
          clientsSync.registerClient(acc.password).flatMap {
            case Right(state) =>
              verbose(s"Client registration complete: $state")
              if (state.clientId.isEmpty) {
                sync.syncSelfClients()
              }
              (account.clientStatePref := state).map(_ => Right(()))
            case Left(err) =>
              error(s"client registration failed: $err")
              Future.successful(Left(err))
          }
        })
    } yield resp
  }
}

class AccountManager(account: AccountDataNew, val global: GlobalModule, val accounts: AccountsServiceImpl)(implicit ec: EventContext) { self =>
  import AccountManager._
  implicit val dispatcher = new SerialDispatchQueue()

  val userId = account.userId
  verbose(s"Creating for: $userId")

  val storage = global.factory.baseStorage(userId)

  val userPrefs = storage.userPrefs

  val teamIdSetPref = userPrefs.preference(TeamIdSet)
  val selfPermsPref = userPrefs.preference(SelfPermissionsKey)
  val copyPermsPref = userPrefs.preference(CopyPermissionsKey)

  val clientStatePref = userPrefs.preference(SelfClient)
  val clientId = clientStatePref.signal.map(_.clientId)

  private val otrClients = storage.otrClientsStorage.signal(userId)
    .map(_.clients.values.toSet)
    .orElse(Signal.const(Set.empty[Client]))

  // listen to client changes, logout and delete cryptobox if current client is removed
  private val otrCurrentClient = clientId.flatMap {
    case Some(cId) => otrClients.map(_.find(_.id == cId))
    case _ => Signal const Option.empty[Client]
  }

  private var hasClient = false
  otrCurrentClient.map(_.isDefined) { exists =>
    if (hasClient && !exists) {
      info(s"client has been removed on backend, logging out")
      OnRemovedClient ! userId
      logoutAndResetClient()
    }
    hasClient = exists
  }

  updateSelfTeam()

  private val selfUserData = storage.usersStorage.signal(userId)

  selfUserData.map(_.deleted) { deleted =>
    if (deleted) {
      info(s"self user was deleted, logging out")
      OnSelfDeleted ! userId
      for {
        _ <- logoutAndResetClient()
        _ =  accounts.accountMap.remove(userId)
        _ <- global.accountsStorageNew.remove(userId)
      // TODO: delete database, account was deleted
      } yield ()
    }
  }

  lazy val cryptoBox          = global.factory.cryptobox(userId, storage)
  lazy val auth               = global.factory.auth(userId)
  lazy val netClient          = global.factory.client(auth)
  lazy val usersClient        = global.factory.usersClient(netClient)
  lazy val teamsClient        = global.factory.teamsClient(netClient)
  lazy val credentialsClient  = global.factory.credentialsClient(netClient)
  lazy val userModule         = global.factory.userModule(userId, this)

  global.accountsStorageNew.onDeleted.filter(_.contains(userId)).on(dispatcher) { _ =>
    ExpiredCookie ! userId
    logout(flushCredentials = true)
  }

  // logged in zmessaging instance
  @volatile private var _zmessaging = Option.empty[ZMessaging]

  lazy val shouldSyncPref = storage.userPrefs.preference(ShouldSyncInitial)
  shouldSyncPref.signal.onChanged.filter(_ == true) { _ =>
    userModule.sync.performFullSync().flatMap(_ => shouldSyncPref := false)
  }

  val zmessaging = (for {
    true       <- teamIdSetPref.signal
    tId        <- storage.usersStorage.signal(userId).map(_.teamId)
    Some(_)    <- Signal.future(cryptoBox.cryptoBox)
    Some(cId)  <- clientId
  } yield {
    verbose(s"Creating new ZMessaging instance, for $userId, $cId, $tId, service: $this")
    _zmessaging = _zmessaging orElse LoggedTry(global.factory.zmessaging(tId, cId, userModule, storage, cryptoBox)).toOption
    _zmessaging
  }).orElse(Signal const Option.empty[ZMessaging])

  for {
    acc      <- accountData if acc.verified
    loggedIn <- accounts.accountState(id).map(_ != LoggedOut)
    client   <- otrCurrentClient
    _        <- otrClients.map(_.size)
  } {
    if (acc.userId.isEmpty || client.isEmpty) {
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

  (for {
    true      <- accounts.accountState(id).map(_ == InForeground)
    Some(acc) <- global.accountsStorage.optSignal(id)
  } yield
    !acc.verified && acc.password.isDefined && (acc.pendingPhone.isDefined || acc.pendingEmail.isDefined)
  ).orElse(Signal.const(false)).on(dispatcher) {
    case true   => awaitActivationFuture = awaitActivationFuture.recover { case _: Throwable => () } flatMap { _ => awaitActivation(0) }
    case false  => awaitActivationFuture.cancel()("stop_await_activate")
  }

  def logout(flushCredentials: Boolean): Future[Unit] = {
    verbose(s"logout($userId, flushCredentials: $flushCredentials)")
    accounts.logout(id, flushCredentials)
  }

  def getZMessaging: Future[Option[ZMessaging]] = zmessaging.head flatMap {
    case Some(zms) => Future successful Some(zms)
    case None =>
      Serialized.future(this) {
        global.accountsStorageNew.get(id) flatMap {
          case Some(acc) if acc.cookie.isEmpty =>
            verbose(s"account data has no cookie, user not logged in: $acc")
            Future successful None
          case Some(_) =>
            for {
              client <- clientId.head
              resp   <- ensureFullyRegistered() flatMap {
                case Right(a) if a.userId.isDefined && client.isDefined && a.verified =>
                  zmessaging.filter(_.isDefined).head // wait until loaded
                case _ =>
                  zmessaging.head
              }
            } yield resp
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
        global.accountsStorage.update(id, _.copy(password = Some(newPassword))) flatMap { _ =>
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
      selfClientId <- clientId
      fingerprint  <-
        if (userId == uId && selfClientId.contains(cId))
          Signal.future(cryptoBox(Future successful _.getLocalFingerprint))
        else
          cryptoBox.sessions.remoteFingerprint(sessionId(uId, cId))
    } yield fingerprint

  private def updateSelfAccountAndUser(acc: AccountData => AccountData, user: UserData => UserData) = {
    for {
      _         <- global.accountsStorage.update(id, acc)
      Some(zms) <- getZMessaging
      _         <- zms.usersStorage.update(zms.selfUserId, user)
    } yield {}
  }

  private def updateSelfTeam(): ErrorOr[Unit] = teamIdSetPref.apply().flatMap {
    case true  => Future.successful(Right({}))
    case false => teamsClient.findSelfTeam().future flatMap {
      case Right(teamOpt) =>
        for {
          _ <- teamOpt.fold2(Future.successful({}), team =>
            for {
              _ <- storage.usersStorage.update(userId, _.copy(teamId = Some(team.id))).map(_ => {})
              _ <- global.teamsStorage.updateOrCreate(team.id, _ => team, team)
              _ <- teamsClient.getPermissions(team.id, userId).future.flatMap {
                case Right((self, copy)) =>
                  for {
                    _ <- selfPermsPref := self
                    _ <- copyPermsPref := copy
                  } yield {}
                case Left(_) => Future.successful({})
              }
            } yield {})
          _ <- teamIdSetPref := true
        } yield Right({})
      case Left(err) => Future.successful(Left(err))
    }
  }

  def ensureFullyRegistered(): Future[Either[ErrorResponse, AccountData]] = {
    verbose(s"ensureFullyRegistered()")

    def updateSelfUser(accountId: AccountId): ErrorOr[Unit] =
      global.accountsStorage.get(accountId).flatMap { account =>
        if (account.exists(_.userId.isDefined)) Future.successful(Right({}))
        else {
          usersClient.loadSelf().future flatMap {
            case Right(userInfo) =>
              verbose(s"got self user info: $userInfo")
              for {
                _ <- storage.assetsStorage.mergeOrCreateAsset(userInfo.mediumPicture)
                _ <- storage.usersStorage.updateOrCreate(userInfo.id, _.updated(userInfo).copy(syncTimestamp = System.currentTimeMillis()), UserData(userInfo).copy(connection = UserData.ConnectionStatus.Self, syncTimestamp = System.currentTimeMillis()))
                _ <- global.accountsStorage.update(id, _.updated(userInfo))
              } yield Right({})
            case Left(err) =>
              verbose(s"loadSelfUser failed: $err")
              Future successful Left(err)
          }
        }
      }

    def checkCryptoBox =
      cryptoBox.cryptoBox.flatMap {
        case Some(cb) => Future successful Some(cb)
        case None =>
          _zmessaging = None
          for {
            _ <- clientStatePref := Unregistered
            _ <- cryptoBox.deleteCryptoBox()
            res <- cryptoBox.cryptoBox
          } yield res
      }

    (for {
      Some(_)      <- checkCryptoBox
      Right(_)     <- updateSelfUser(id)
      Right(_)     <- userModule.head.flatMap(_.ensureClientRegistered(id))
      Right(_)     <- updateSelfTeam(id)
      Some(after)  <- global.accountsStorage.get(id)
    } yield Right(after))
      .recover {
        case NonFatal(_) =>
          val msg = s"Error during registration for account: $id"
          warn(msg)
          Left(ErrorResponse.internalError(msg))
      }
  }

//  TODO move to AccountsService
//  private def activate(accountId: AccountId): ErrorOr[AccountData] =
//    global.accountsStorage.get(accountId).flatMap {
//      case Some(account) =>
//        if (account.verified && !account.regWaiting) Future successful Right(account)
//        else global.loginClient.login(PendingAccount(account)).future flatMap {
//          case Right((token, cookie)) =>
//            for {
//              Some((_, acc)) <- global.accountsStorage.update(id, _.updatedNonPending.copy(cookie = cookie, accessToken = Some(token)))
//            } yield Right(acc)
//          case Left((_, ErrorResponse(Status.Forbidden, _, "pending-activation"))) =>
//            global.accountsStorage.update(accountId, _.updatedPending).collect { case Some((_, acc)) => Left(ErrorResponse(Status.Forbidden, "", "pending-activation"))}
//          case Left((_, err)) =>
//            verbose(s"activate failed: $err")
//            Future.successful(Left(err))
//        }
//      case None => Future.successful(Left(ErrorResponse.internalError(s"no account for $accountId")))
//    }


  // TODO move to AccountsService
//  private def awaitActivation(retry: Int = 0): CancellableFuture[Option[AccountData]] =
//    CancellableFuture lift global.accountsStorage.get(id) flatMap {
//      case None => CancellableFuture successful None
//      case Some(data) if data.verified => CancellableFuture successful Some(data)
//      case Some(data) =>
//        CancellableFuture.lift(accounts.accountState(id).map(_ == InForeground).head).flatMap {
//          case true => CancellableFuture.lift(activate(data.id)).flatMap {
//            case Right(acc) if acc.verified => CancellableFuture successful Some(acc)
//            case _ =>
//              CancellableFuture.delay(ActivationThrottling.delay(retry)) flatMap { _ => awaitActivation(retry + 1) }
//          }
//          case false => CancellableFuture.successful(None)
//        }
//    }

  private def logoutAndResetClient() =
    for {
      _ <- logout(flushCredentials = true)
      _ <- cryptoBox.deleteCryptoBox()
      _ =  _zmessaging = None // drop zmessaging instance, we need to create fresh one with new clientId // FIXME: dropped instance will still be active and using the same ZmsLifecycle instance
      _ <- clientStatePref := Unregistered
    } yield ()
}

object AccountManager {

  trait ClientRegistrationState {
    val clientId: Option[ClientId] = None
  }

  object ClientRegistrationState {
    case object Unregistered    extends ClientRegistrationState
    case object PasswordMissing extends ClientRegistrationState
    case object LimitReached    extends ClientRegistrationState
    case class  Registered(cId: ClientId) extends ClientRegistrationState {
      override val clientId = Some(cId)
    }
  }

  val ActivationThrottling = new ExponentialBackoff(2.seconds, 15.seconds)

  val OnRemovedClient: SourceStream[UserId] = EventStream[UserId]()
  val OnSelfDeleted: SourceStream[UserId] = EventStream[UserId]()
  val ExpiredCookie: SourceStream[UserId] = EventStream[UserId]()
}
