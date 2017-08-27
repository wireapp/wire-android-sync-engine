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

import com.waz.content.GlobalPreferences.PushEnabledKey
import com.waz.content.{AccountsStorage, GlobalPreferences}
import com.waz.model._
import com.waz.service.ZmsLifeCycle
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.testutils.TestGlobalPreferences
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.utils.wrappers.GoogleApi

import scala.concurrent.Future

class PushTokenServiceSpec extends AndroidFreeSpec {

  val google      = mock[GoogleApi]
  val lifecycle   = mock[ZmsLifeCycle]
  val accStorage  = mock[AccountsStorage]
  val prefs       = new TestGlobalPreferences()

  val sync        = mock[SyncServiceHandle]
  val accountId   = AccountId()

  val pushEnabled         = prefs.preference(PushEnabledKey)
  val currentToken        = prefs.preference(GlobalPreferences.PushToken)

  val googlePlayAvailable = Signal(false)
  val accInForeground     = Signal(false)
  val accountSignal       = Signal[AccountData]()

  val loggedInAccounts    = Signal(Set.empty[AccountData])

  def accountData(accountId: AccountId, token: PushToken): AccountData = AccountData(accountId, registeredPush = Some(token))
  def accountData(accountId: AccountId, token: Option[PushToken]): AccountData = AccountData(accountId, registeredPush = token)

  feature("Token generation") {
    scenario("Fetches token on init if GCM available") {
      val token = PushToken("token")
      (google.getPushToken _).expects().returning(Some(token))
      val service = initTokenService()

      pushEnabled := true
      googlePlayAvailable ! true
      result(service.currentToken.signal.filter(_.contains(token)).head)
    }

    scenario("Remove Push Token event should create new token and delete all previous tokens") {

      val oldToken = PushToken("oldToken")
      val newToken = PushToken("newToken")
      pushEnabled := true
      currentToken := Some(oldToken)
      googlePlayAvailable ! true

      (google.getPushToken _).expects().once().returning(Some(newToken))

      //This needs to be called
      (google.deleteAllPushTokens _).expects().once()

      val service = initTokenService()

      //wait for first token to be set
      result(service.currentToken.signal.filter(_.contains(oldToken)).head)
      //delete first token in response to BE event
      service.eventProcessingStage(RConvId(), Vector(PushTokenRemoveEvent(oldToken, "sender", Some("client"))))
      //new token should be set
      result(service.currentToken.signal.filter(_.contains(newToken)).head)
    }
  }

  feature("Token registration") {
    scenario("If current user does not have matching registeredPush token, remove the old token and register the new one with our BE") {

      val oldToken = PushToken("oldToken")
      val newToken = PushToken("token")

      loggedInAccounts ! Set(accountData(accountId, oldToken))

      currentToken := Some(newToken)
      pushEnabled := true
      googlePlayAvailable ! true

      lazy val service = initTokenService()

      (sync.deletePushToken _).expects(oldToken).once().returning(Future.successful(SyncId()))

      (sync.registerPush _).expects(newToken).once().onCall { _: PushToken =>
        Future {
          service.onTokenRegistered(newToken)
          SyncId()
        } (Threading.Background)
      }

      result(service.currentToken.signal.filter(_.contains(newToken)).head)
      result(loggedInAccounts.filter(_.exists(acc => acc.id == accountId && acc.registeredPush.contains(newToken))).head)
    }

    scenario("Instance Id token refresh should trigger re-registration for current user") {
      val oldToken = PushToken("oldToken")
      val newToken = PushToken("newToken")

      loggedInAccounts ! Set(accountData(accountId, oldToken))
      pushEnabled := true
      googlePlayAvailable ! true
      currentToken := Some(oldToken)

      lazy val (globalToken, service) = initTokenServiceWithGlobal()

      (sync.deletePushToken _).expects(oldToken).once().returning(Future.successful(SyncId()))
      (sync.registerPush _).expects(newToken).once().onCall { _: PushToken =>
        Future {
          service.onTokenRegistered(newToken)
          SyncId()
        } (Threading.Background)
      }

      result(service.currentToken.signal.filter(_.contains(oldToken)).head)
      result(loggedInAccounts.filter(_.exists(acc => acc.id == accountId && acc.registeredPush.contains(oldToken))).head)

      globalToken.setNewToken(Some(newToken)) //InstanceIDService triggers new token

      result(service.currentToken.signal.filter(_.contains(newToken)).head)
      result(loggedInAccounts.filter(_.exists(acc => acc.id == accountId && acc.registeredPush.contains(newToken))).head)
    }

    scenario("After user is logged out, clearing their current push token should NOT trigger new registration") {
      val token = PushToken("token")

      loggedInAccounts ! Set(accountData(accountId, token))
      pushEnabled := true
      googlePlayAvailable ! true
      currentToken := Some(token)

      (google.getPushToken _).expects().never()
      (sync.registerPush _).expects(*).never()

      lazy val service = initTokenService()

      result(service.pushActive.filter(_ == true).head)

      loggedInAccounts ! Set.empty //user is logged out

      result(service.pushActive.filter(_ == false).head)

      /**
        * It seems as though there can be a couple of instances of zms (and therefore the push token service) available.
        * So lets make sure we only register the account assigned to our instance. Logging in then with another account
        * shouldn't change the state of this instance.
        */
      loggedInAccounts ! Set(AccountData())

      awaitAllTasks
      result(service.pushActive.filter(_ == false).head)
    }
  }

  feature("Push active") {

    scenario("Push should be active if push is enabled and inactive if not") {
      val token = PushToken("token")
      loggedInAccounts ! Set(accountData(accountId, token))
      pushEnabled := true //set active
      googlePlayAvailable ! true
      currentToken := Some(token)

      val service = initTokenService()

      result(service.pushActive.filter(_ == true).head)

      pushEnabled := false //set inactive
      result(service.pushActive.filter(_ == false).head)
    }

    scenario("Push should be active if app is in background and inactive if not") {
      val token = PushToken("token")
      loggedInAccounts ! Set(accountData(accountId, token))
      pushEnabled := true
      googlePlayAvailable ! true
      accInForeground ! true //websocket should be open
      currentToken := Some(token)

      val service = initTokenService()

      result(service.pushActive.filter(_ == false).head)

      accInForeground ! false //websocket should be off - use push again
      result(service.pushActive.filter(_ == true).head)
    }

    scenario("Push should be inactive if play services are unavailable") {
      val token = PushToken("token")
      loggedInAccounts ! Set(accountData(accountId, token))
      pushEnabled := true
      googlePlayAvailable ! true
      currentToken := Some(token)

      val service = initTokenService()

      result(service.pushActive.filter(_ == true).head)

      googlePlayAvailable ! false //Set true and then false again to cause the signal to change. Maybe the user disables play services?
      result(service.pushActive.filter(_ == false).head)
    }

    scenario("Push should be inactive if user is not currently registered") {

      val token = PushToken("token")
      loggedInAccounts ! Set(accountData(accountId, None))
      pushEnabled := true
      googlePlayAvailable ! true
      currentToken := Some(token)

      (sync.registerPush _).expects(*).once().returning(Future.successful(SyncId()))
      val service = initTokenService()
      result(service.pushActive.filter(_ == false).head)
    }
  }

  feature("multiple accounts") {
    scenario("Reset global token") {
      val oldToken = PushToken("oldToken")
      val newToken = PushToken("newToken")

      currentToken := Some(oldToken)
      await(currentToken.signal.filter(_.contains(oldToken)).head)

      (google.getPushToken _).expects().once().returning(Some(newToken))
      //This needs to be called
      (google.deleteAllPushTokens _).expects().once()

      val (globalToken, _) = initTokenServiceWithGlobal()

      globalToken.resetGlobalToken()
      await(currentToken.signal.filter(_.contains(newToken)).head)
    }

    scenario("Add second account and it should get the current global token") {

      val token = PushToken("oldToken")

      //Start off
      val account1 = accountData(accountId, Some(token))
      val account2 = accountData(AccountId(), None)

      loggedInAccounts ! Set(account1, account2)
      pushEnabled := true
      googlePlayAvailable ! true
      currentToken := Some(token)

      val sync2    = mock[SyncServiceHandle]

      val global = initGlobal //need to share the same global instance
      lazy val service1 = initTokenService(global = global)
      lazy val service2 = initTokenService(account2.id, sync2, global = global)

      (sync2.registerPush _).expects(token).once().onCall { _: PushToken =>
        Future {
          service2.onTokenRegistered(token)
          SyncId()
        } (Threading.Background)
      }

      //trigger lazy vals
      service1
      service2

      await(loggedInAccounts.filter { accs =>
        accs.exists(acc => acc.id == accountId && acc.registeredPush.contains(token)) &&
        accs.exists(acc => acc.id == account2.id && acc.registeredPush.contains(token))
      }.head)
    }
  }

  def initGlobal = {
    (google.isGooglePlayServicesAvailable _).expects().anyNumberOfTimes().returning(googlePlayAvailable)
    new GlobalTokenService(google, prefs)
  }

  def initTokenService(accountId: AccountId = accountId, sync: SyncServiceHandle = sync, global: GlobalTokenService = initGlobal) = initTokenServiceWithGlobal(accountId, sync, global)._2

  def initTokenServiceWithGlobal(accountId: AccountId = accountId, sync: SyncServiceHandle = sync, global: GlobalTokenService = initGlobal) = {
    (accStorage.signal _).expects(*).anyNumberOfTimes().onCall { id: AccountId =>
      loggedInAccounts.map(_.find(_.id == id)).collect { case Some(acc) => acc }
    }
    (lifecycle.accInForeground _).expects(accountId).anyNumberOfTimes().returning(accInForeground)
    (accStorage.update _).expects(*, *).anyNumberOfTimes().onCall { (id, f) =>
      Future.successful {
        val account = loggedInAccounts.currentValue("").flatMap(_.find(_.id == id))

        returning(account.fold(Option.empty[(AccountData, AccountData)])(p => Some((p, f(p))))) {
          case Some((_, updated)) => loggedInAccounts.mutate { accs =>
            val removed = accs - accs.find(_.id == updated.id).get
            val res = removed + updated
            res
          }
          case _ =>
        }
      }
    }
    (global, new PushTokenService(google, global, prefs, lifecycle, accountId, loggedInAccounts.map(_.map(_.id)), accStorage, sync))
  }
}
