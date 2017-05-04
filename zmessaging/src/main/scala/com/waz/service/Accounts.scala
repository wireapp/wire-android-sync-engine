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

import com.waz.ZLog._
import com.waz.api.impl._
import com.waz.api.{KindOfAccess, KindOfVerification}
import com.waz.client.RegistrationClient.ActivateResult
import com.waz.model._
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.events.{EventContext, Signal}
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient._

import scala.collection.mutable
import scala.concurrent.Future

class Accounts(val global: GlobalModule) {

  import Accounts._
  implicit val dispatcher = new SerialDispatchQueue(name = "InstanceService")

  private[waz] implicit val ec: EventContext = EventContext.Global

  private[waz] val accountMap = new mutable.HashMap[AccountId, AccountService]()

  val context       = global.context
  val prefs         = global.prefs
  val storage       = global.accountsStorage
  val phoneNumbers  = global.phoneNumbers
  val regClient     = global.regClient
  val loginClient   = global.loginClient

  val currentAccountPref = prefs.preference[String](CurrentAccountPref, "")

  lazy val currentAccountData = currentAccountPref.signal.flatMap[Option[AccountData]] {
    case "" => Signal.const(Option.empty[AccountData])
    case idStr => storage.optSignal(AccountId(idStr))
  }

  lazy val current = currentAccountData.flatMap[Option[AccountService]] {
    case None      => Signal const None
    case Some(acc) => Signal.future(getInstance(acc) map (Some(_)))
  }

  lazy val currentZms: Signal[Option[ZMessaging]] =
    current.flatMap[Option[ZMessaging]] {
      case Some(service) => service.zmessaging
      case None          => Signal const None
    }

  def getCurrentAccountInfo = currentAccountPref() flatMap {
    case "" => Future successful None
    case idStr => storage.get(AccountId(idStr))
  }

  def getCurrent = getCurrentAccountInfo flatMap {
    case Some(acc) => getInstance(acc) map (Some(_))
    case _ => Future successful None
  }

  def getCurrentZms = getCurrent.flatMap {
    case Some(acc)  => acc.getZMessaging
    case None       => Future successful None
  }

  private[service] def getInstance(account: AccountData) = Future {
    accountMap.getOrElseUpdate(account.id, new AccountService(account, global, this))
  }

  def getInstance(id: AccountId): Future[Option[AccountService]] = storage.get(id) flatMap {
    case Some(acc) =>
      verbose(s"getInstance($acc)")
      getInstance(acc) map (Some(_))
    case _ =>
      Future successful None
  }

  def logout() = current.head flatMap {
    case Some(account) => account.logout()
    case None => Future.successful(())
  }

  def logout(account: AccountId) = currentAccountPref() flatMap {
    case id if id == account.str => setAccount(None) map (_ => ())
    case id =>
      verbose(s"logout($account) ignored, current id: $id")
      Future.successful(())
  }

  private def setAccount(acc: Option[AccountId]) = {
    verbose(s"setAccount($acc)")
    currentAccountPref := acc.fold("")(_.str)
  }

  private def switchAccount(credentials: Credentials) = {
    verbose(s"switchAccount($credentials)")
    for {
      _          <- logout()
      normalized <- normalizeCredentials(credentials)
      matching   <- storage.find(normalized)
      account    =  matching.flatMap(_.authorized(normalized))
      _          <- setAccount(account.map(_.id))
      service    <- account.fold(Future successful Option.empty[AccountService]) { a => getInstance(a).map(Some(_)) }
    } yield
      (normalized, matching, service)
  }


  def login(credentials: Credentials): Future[Either[ErrorResponse, AccountData]] =
    switchAccount(credentials) flatMap {
      case (normalized, _, Some(service)) => service.login(normalized)
      case (normalized, Some(account), None) => // found matching account, but is not authorized (wrong password)
        verbose(s"found matching account: $account, trying to authorize with backend")
        login(account, normalized)
      case (normalized, None, None) =>
        verbose(s"matching account not found, creating new account")
        login(new AccountData(AccountId(), None, "", None, handle = None), normalized)
    }

  private def login(account: AccountData, normalized: Credentials) = {
    def loginOnBackend() =
      loginClient.login(account.id, normalized).future map {
        case Right((token, c)) =>
          Right(account.updated(normalized).copy(cookie = c, activated = true, accessToken = Some(token)))
        case Left((_, error @ ErrorResponse(Status.Forbidden, _, "pending-activation"))) =>
          verbose(s"account pending activation: $normalized, $error")
          Right(account.updated(normalized).copy(activated = false, cookie = None, accessToken = None))
        case Left((_, error)) =>
          verbose(s"login failed: $error")
          Left(error)
      }

    loginOnBackend() flatMap {
      case Right(a) =>
        for {
          acc     <- storage.updateOrCreate(a.id, _.updated(normalized).copy(cookie = a.cookie, activated = true, accessToken = a.accessToken), a)
          service <- getInstance(acc)
          _       <- setAccount(Some(acc.id))
          res     <- service.login(normalized)
        } yield res
      case Left(err) =>
        Future successful Left(err)
    }
  }

  def requestVerificationEmail(email: EmailAddress): Unit = loginClient.requestVerificationEmail(email)

  def requestPhoneConfirmationCode(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] =
    CancellableFuture.lift(phoneNumbers.normalize(phone)) flatMap { normalizedPhone =>
      regClient.requestPhoneConfirmationCode(normalizedPhone.getOrElse(phone), kindOfAccess)
    }

  def requestPhoneConfirmationCall(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] =
    CancellableFuture.lift(phoneNumbers.normalize(phone)) flatMap { normalizedPhone =>
      regClient.requestPhoneConfirmationCall(normalizedPhone.getOrElse(phone), kindOfAccess)
    }

  def verifyPhoneNumber(phone: PhoneCredentials, kindOfVerification: KindOfVerification): ErrorOrResponse[Unit] =
    CancellableFuture.lift(phoneNumbers.normalize(phone.phone)) flatMap { normalizedPhone =>
      regClient.verifyPhoneNumber(PhoneCredentials(normalizedPhone.getOrElse(phone.phone), phone.code), kindOfVerification)
    }

  private def normalizeCredentials(credentials: Credentials): Future[Credentials] = credentials match {
    case cs @ PhoneCredentials(p, _, _) =>
      phoneNumbers.normalize(p) map { normalized => cs.copy(phone = normalized.getOrElse(p)) }
    case other =>
      Future successful other
  }

  def register(credentials: Credentials, name: String, accent: AccentColor): Future[Either[ErrorResponse, AccountData]] = {
    debug(s"register($credentials, $name, $accent")

    def register(accountId: AccountId, normalized: Credentials) =
      regClient.register(accountId, normalized, name, Some(accent.id)).future flatMap {
        case Right((userInfo, cookie)) =>
          verbose(s"register($credentials) done, id: $accountId, user: $userInfo, cookie: $cookie")
          for {
            acc     <- storage.insert(AccountData(accountId, normalized).copy(cookie = cookie, userId = Some(userInfo.id), activated = normalized.autoLoginOnRegistration))
            _       = verbose(s"created account: $acc")
            service <- getInstance(acc)
            _       <- setAccount(Some(accountId))
            res     <- service.login(normalized)
          } yield res
        case Left(error) =>
          info(s"register($credentials, $name) failed: $error")
          Future successful Left(error)
      }

    switchAccount(credentials) flatMap {
      case (normalized, _, Some(service)) =>
        verbose(s"register($credentials), found matching account: $service, will just sign in")
        service.login(normalized)
      case (normalized, Some(account), None) =>
        verbose(s"register($credentials), found matching account: $account, will try signing in")
        login(account, normalized) flatMap {
          case Right(acc) => Future successful Right(acc)
          case Left(_) =>
            // login failed, maybe this account has been removed on backend, let's try registering
            register(account.id, normalized)
        }
      case (normalized, None, None) =>
        register(AccountId(), normalized)
    }
  }
}

object Accounts {
  private implicit val logTag: LogTag = logTagFor[Accounts]

  val CurrentAccountPref = "CurrentUserPref"
}

