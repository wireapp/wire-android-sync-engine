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
import com.waz.ZLog._
import com.waz.api.ClientRegistrationState
import com.waz.api.impl._
import com.waz.content.Preference
import com.waz.model.{UserData, _}
import com.waz.model.otr.Client
import com.waz.service.otr.{OtrClientsService, VerificationStateUpdater}
import com.waz.sync._
import com.waz.sync.client.OtrClient
import com.waz.sync.otr.OtrClientsSyncHandler
import com.waz.threading.{CancellableFuture, SerialDispatchQueue, Threading}
import com.waz.utils._
import com.waz.utils.events.{EventContext, Signal}
import com.waz.znet.AuthenticationManager._
import com.waz.znet.CredentialsHandler
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Right

class UserModule(val userId: UserId, val account: AccountService) {
  import Threading.Implicits.Background
  private implicit val Tag: LogTag = logTagFor[UserModule]

  def context = account.global.context
  def db = account.storage.db
  def clientId = account.clientId
  def accountId = account.id
  def timeouts = account.global.timeouts
  def network = account.global.network
  def kvStorage = account.storage.kvStorage
  def usersStorage = account.storage.usersStorage
  def convsStorage = account.storage.convsStorage
  def membersStorage = account.storage.membersStorage
  def clientsStorage = account.storage.otrClientsStorage
  def lifecycle = account.global.lifecycle
  def cryptoBox = account.cryptoBox
  def reporting = account.global.reporting

  lazy val otrClient = new OtrClient(account.netClient)

  lazy val verificationUpdater                = wire[VerificationStateUpdater]
  lazy val clientsService: OtrClientsService  = wire[OtrClientsService]
  lazy val clientsSync: OtrClientsSyncHandler = wire[OtrClientsSyncHandler]

  lazy val sync: SyncServiceHandle            = wire[AndroidSyncServiceHandle]
  lazy val syncRequests: SyncRequestService   = wire[SyncRequestService]
  lazy val syncHandler: SyncHandler           = new AccountSyncHandler(account.zmessaging.collect { case Some(zms) => zms }, clientsSync)

  def ensureClientRegistered(account: AccountData): Future[Either[ErrorResponse, AccountData]] =
    if (account.clientId.isDefined) Future successful Right(account)
    else {
      import com.waz.api.ClientRegistrationState._
      clientsSync.registerClient(account.password) map {
        case Right((REGISTERED, Some(cl))) =>
          Right(account.copy(clientId = Some(cl.id), clientRegState = REGISTERED, verified = true))
        case Right((state, _)) =>
          sync.syncSelfClients() // request clients sync, UI will need that
          Right(account.copy(clientRegState = state))
        case Left(err) =>
          error(s"client registration failed: $err")
          Left(err)
      }
    }
}

class AccountService(@volatile var account: AccountData, val global: GlobalModule, accounts: Accounts)(implicit ec: EventContext) { self =>
  import AccountService._
  private implicit val dispatcher = new SerialDispatchQueue()

  val id = account.id

  import global._

  lazy val storage: StorageModule = returning(global.factory.baseStorage(id)) { storage =>

    account.userId foreach { userId =>
      // ensure that self user is present on start
      storage.usersStorage.updateOrCreate(userId, identity, UserData(userId, "", account.email, account.phone, searchKey = SearchKey(""), connection = UserData.ConnectionStatus.Self, handle = account.handle))
    }

    val selfUserData = accountData.map(_.userId).flatMap {
      case Some(userId) => storage.usersStorage.optSignal(userId)
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

    // listen to client changes, logout and delete cryptobox if current client is removed
    val otrClient = accountData.map(a => (a.userId, a.clientId)).flatMap {
      case (Some(userId), Some(cId)) => storage.otrClientsStorage.optSignal(userId).map(_.flatMap(_.clients.get(cId)))
      case _                         => Signal const Option.empty[Client]
    }
    var hasClient = false
    otrClient.map(_.isDefined) { exists =>
      if (hasClient && !exists) {
        info(s"client has been removed on backend, logging out")
        logoutAndResetClient()
      }
      hasClient = exists
    }
  }

  lazy val cryptoBox          = global.factory.cryptobox(id, storage)
  lazy val netClient          = global.factory.client(credentialsHandler)
  lazy val usersClient        = global.factory.usersClient(netClient)
  lazy val credentialsClient  = global.factory.credentialsClient(netClient)

  @volatile private var _userModule = Option.empty[UserModule]

  lazy val userModule = userId collect {
    case Some(user) =>
      _userModule = Some(_userModule.getOrElse(global.factory.userModule(user, this)))
      _userModule.get
  }

  @volatile
  private[waz] var credentials = Credentials.Empty

  lazy val credentialsHandler = new CredentialsHandler {
    override val userId: AccountId = id
    override val cookie: Preference[Option[Cookie]] = Preference[Option[Cookie]](None, accountsStorage.get(id).map(_.flatMap(_.cookie)), { c => accountsStorage.update(id, _.copy(cookie = c)) })
    override val accessToken: Preference[Option[Token]] = Preference[Option[Token]](None, accountsStorage.get(id).map(_.flatMap(_.accessToken)), { token => accountsStorage.update(id, _.copy(accessToken = token)) })
    override def credentials: Credentials = self.credentials

    override def onInvalidCredentials(): Unit = logout()
  }

  lazy val accountData = accountsStorage.signal(id)

  lazy val clientId = accountData.map(_.clientId)

  lazy val userId = accountData.map(_.userId)

  // logged in zmessaging instance
  @volatile private var _zmessaging = Option.empty[ZMessaging]

  val zmessaging = clientId flatMap {
    case None => Signal const Option.empty[ZMessaging]
    case Some(cId) =>
      userModule flatMap { um =>
        Signal.future {
          cryptoBox.cryptoBox mapOpt { _ =>
            verbose(s"Creating new ZMessaging instance, for $um, $cId, service: $this")
            _zmessaging = _zmessaging orElse LoggedTry(global.factory.zmessaging(cId, um)).toOption
            _zmessaging
          }
        }
      }
  }

  val isLoggedIn = accounts.currentAccountPref.signal.map(_ == id.str)

  accountData { acc =>
    account = acc
    if (acc.cookie.isDefined) {
      if (credentials == Credentials.Empty) credentials = acc.credentials
    }
  } (EventContext.Global)

  accountData.zip(isLoggedIn) {
    case (acc, loggedIn) =>
      if (loggedIn && acc.verified && (acc.userId.isEmpty || acc.clientId.isEmpty)) {
        verbose(s"account data needs registration: $acc")
        Serialized.future(self) { ensureFullyRegistered() }
      }
  }

  isLoggedIn.on(Threading.Ui) { lifecycle.setLoggedIn }

  private var awaitActivationFuture = CancellableFuture successful Option.empty[AccountData]

  private val shouldAwaitActivation = lifecycle.uiActive.zip(accountsStorage.optSignal(id)) map {
    case (true, Some(acc)) => !acc.verified && acc.password.isDefined
    case _ => false
  }

  shouldAwaitActivation.on(dispatcher) {
    case true   => awaitActivationFuture = awaitActivationFuture.recover { case _: Throwable => () } flatMap { _ => awaitActivation(0) }
    case false  => awaitActivationFuture.cancel()("stop_await_activate")
  }

  lifecycle.lifecycleState { state => verbose(s"lifecycle state: $state") }

  def login(credentials: Credentials): Future[Either[ErrorResponse, AccountData]] =
    Serialized.future(this) {
      verbose(s"login($credentials)")
      self.credentials = credentials
      accountsStorage.updateOrCreate(id, _.updated(credentials), account.updated(credentials)) flatMap { _ => ensureFullyRegistered() }
    }

  def logout(): Future[Unit] = {
    verbose(s"logout($id)")
    accounts.logout(id)
  }

  def getZMessaging: Future[Option[ZMessaging]] = zmessaging.head flatMap {
    case Some(zms) => Future successful Some(zms)
    case None =>
      Serialized.future(this) {
        accountsStorage.get(id) flatMap {
          case Some(ad @ AccountData(_, _, _, _, _, _, _, None, _, _, _, _, _, _)) =>
            verbose(s"account data has no cookie, user not logged in: $ad")
            Future successful None
          case Some(acc) =>
            ensureFullyRegistered() flatMap {
              case Right(a @ AccountData(_, _, _, _, _, _, _, _, _, _, Some(_), Some(_), _, _)) if a.verified =>
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
  def updatePhone(phone: PhoneNumber): ErrorOrResponse[Unit] = credentialsClient.updatePhone(phone)
  def updatePassword(newPassword: String, currentPassword: Option[String]) =
    credentialsClient.updatePassword(newPassword, currentPassword).future flatMap {
      case Left(err) => Future successful Left(err)
      case Right(_) =>
        accountsStorage.update(id, _.copy(hash = AccountData.computeHash(id, newPassword), password = Some(newPassword))) flatMap { _ =>
          getZMessaging map { _ => Right(()) }
        }
    }
  def updateHandle(handle: Handle): ErrorOrResponse[Unit] = credentialsClient.updateHandle(handle)

  def updatePrivateMode(privateMode: Boolean): ErrorOrResponse[Unit] =
    account.userId match {
      case Some(uId) => usersClient.updateSelf(UserInfo(uId, privateMode = Some(privateMode)))
      case _ => CancellableFuture(Left(ErrorResponse.internalError("User info hasn't been loaded yet")))
    }


  private[service] def ensureFullyRegistered(): Future[Either[ErrorResponse, AccountData]] = {
    verbose(s"ensureFullyRegistered()")

    def loadSelfUser(account: AccountData): Future[Either[ErrorResponse, AccountData]] =
      if (account.userId.isDefined) Future successful Right(account)
      else {
        usersClient.loadSelf().future flatMap {
          case Right(userInfo) =>
            verbose(s"got self user info: $userInfo")
            for {
              _ <- storage.assetsStorage.mergeOrCreateAsset(userInfo.mediumPicture)
              _ <- storage.usersStorage.updateOrCreate(userInfo.id, _.updated(userInfo).copy(syncTimestamp = System.currentTimeMillis()), UserData(userInfo).copy(connection = UserData.ConnectionStatus.Self, syncTimestamp = System.currentTimeMillis()))
              res <- accountsStorage.updateOrCreate(id, _.updated(userInfo), account.updated(userInfo))
            } yield Right(res)
          case Left(err) =>
            verbose(s"loadSelfUser failed: $err")
            Future successful Left(err)
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

    checkCryptoBox flatMap {
      case None => Future successful Left(ErrorResponse.internalError("CryptoBox loading failed"))
      case Some(_) =>
        accountsStorage.get(id) flatMap {
          case None => Future successful Left(ErrorResponse.internalError(s"Missing AccountData for id: $id"))
          case Some(acc) =>
            activate(acc, credentials).future flatMap {
              case Right(acc1) if acc1.verified =>
                loadSelfUser(acc1) flatMap {
                  case Right(acc2) =>
                    // TODO: check if there is some other AccountData with the same userId already present
                    // it may happen that the same account was previously used with different credentials
                    // we should merge those accounts, delete current one, and switch to the previous one
                    userModule.head flatMap { _.ensureClientRegistered(acc2) } flatMap {
                      case Right(acc3) =>
                        accountsStorage.updateOrCreate(id, _.updated(acc3.userId, acc3.verified, acc3.clientId, acc3.clientRegState), acc3) map { Right(_) }
                      case Left(err) => Future successful Left(err)
                    }
                  case Left(err) => Future successful Left(err)
                }
              case Right(acc1) => Future successful Right(acc1)
              case Left(err) => Future successful Left(err)
          }
        }
    }
  }

  private def activate(account: AccountData, credentials: Credentials): CancellableFuture[Either[ErrorResponse, AccountData]] =
    if (account.verified) CancellableFuture successful Right(account)
    else loginClient.login(account.id, credentials) flatMap {
      case Right((token, cookie)) =>
        CancellableFuture lift {
          for {
            _ <- credentialsHandler.cookie := cookie
            _ <- credentialsHandler.accessToken := Some(token)
            acc <- accountsStorage.updateOrCreate(id, _.copy(verified = true, cookie = cookie, accessToken = Some(token)), account.copy(verified = true, cookie = cookie, accessToken = Some(token)))
          } yield Right(acc)
        }
      case Left((_, ErrorResponse(Status.Forbidden, _, "pending-activation"))) =>
        CancellableFuture successful Right(account.copy(verified = false))
      case Left((_, err)) =>
        verbose(s"activate failed: $err")
        CancellableFuture successful Left(err)
    }

  private def awaitActivation(retry: Int = 0): CancellableFuture[Option[AccountData]] =
    CancellableFuture lift accountsStorage.get(id) flatMap {
      case None => CancellableFuture successful None
      case Some(data) if data.verified => CancellableFuture successful Some(data)
      case Some(_) if !lifecycle.isUiActive => CancellableFuture successful None
      case Some(data) =>
        activate(data, credentials) flatMap {
          case Right(acc) if acc.verified => CancellableFuture successful Some(acc)
          case _ =>
            CancellableFuture.delay(ActivationThrottling.delay(retry)) flatMap { _ => awaitActivation(retry + 1) }
        }
    }

  private def logoutAndResetClient() =
    for {
      _ <- logout()
      _ <- cryptoBox.deleteCryptoBox()
      _ =  _zmessaging = None // drop zmessaging instance, we need to create fresh one with new clientId // FIXME: dropped instance will still be active and using the same ZmsLifecycle instance
      _ <- accountsStorage.update(id, _.copy(clientId = None, clientRegState = ClientRegistrationState.UNKNOWN))
    } yield ()
}

object AccountService {
  private implicit val tag: LogTag = logTagFor[AccountService]

  val ActivationThrottling = new ExponentialBackoff(2.seconds, 15.seconds)
}
