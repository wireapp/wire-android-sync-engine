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

import com.waz.ZLog
import com.waz.ZLog.LogLevel
import com.waz.content.{AccountsStorage, Preference}
import com.waz.content.Preference.PrefCodec
import com.waz.model.{AccountData, AccountId, SyncId}
import com.waz.service.push.PushTokenService.pushTokenPrefKey
import com.waz.service.{PreferenceService, ZmsLifecycle}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.threading.Threading
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.utils.returning
import com.waz.utils.wrappers.GoogleApi
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class PushTokenServiceSpec extends FeatureSpec with AndroidFreeSpec with MockFactory with Matchers with BeforeAndAfter {

  import PushTokenService._

  val gcmEnabledKey = "PUSH_ENABLED_KEY"

  val google    = mock[GoogleApi]
  val prefs     = mock[PreferenceService]
  val lifecycle = mock[ZmsLifecycle]
  val accounts  = mock[AccountsStorage]
  val sync      = mock[SyncServiceHandle]
  val accountId = AccountId()

  var pushEnabledPref: Preference[Boolean] = _
  var pushTokenPref: Preference[Option[String]] = _

  var googlePlayAvailable: SourceSignal[Boolean] = _
  var lifecycleActive: SourceSignal[Boolean] = _
  var accountSignal: SourceSignal[AccountData] = _

  val defaultDuration = 5.seconds

  before {
    pushEnabledPref = Preference.inMemory[Boolean](false, if (ZLog.testLogging) Some(gcmEnabledKey) else None)
    pushTokenPref = Preference.inMemory[Option[String]](None, if (ZLog.testLogging) Some(pushTokenPrefKey) else None)

    googlePlayAvailable = Signal(false)
    lifecycleActive = Signal(false)
    accountSignal = Signal[AccountData]()
  }

  feature("Token generation") {
    scenario("Fetches token on init if GCM available") {
      val token = "token"
      (google.getPushToken _).expects().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true
      Await.ready(service.currentTokenPref.signal.filter(_.contains(token)).head, defaultDuration)
    }

    scenario("Remove Push Token event should create new token and delete all previous tokens") {
      fail()
    }

    scenario("If play services is not available, we should never query for a new token") {
      fail()
    }

    scenario("InstanceIdService refresh should generate new token") {
      fail()
    }
  }

  feature("Token registration") {
    scenario("Current: If current user does not have matching registeredPush token, register the user with our BE") {

      accountSignal ! AccountData(accountId, None, "", None, None, Some("oldToken"))
      pushEnabledPref := true
      googlePlayAvailable ! true

      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)

      lazy val service = initTokenService()

      (sync.registerPush _).expects().anyNumberOfTimes().onCall { () =>
        Future {
          service.onTokenRegistered()
          SyncId()
        } (Threading.Background)
      }

      Await.result(service.currentTokenPref.signal.filter(_.contains(token)).head, defaultDuration)
      Await.result(accountSignal.filter(_.registeredPush.contains(token)).head, defaultDuration)
    }

    scenario("Changed token should trigger re-registration for current user") {
      accountSignal ! AccountData(accountId, None, "", None, None, Some("token"))
      pushEnabledPref := true
      googlePlayAvailable ! true

      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)

      lazy val service = initTokenService()

      (sync.registerPush _).expects().anyNumberOfTimes().onCall { () =>
        Future {
          service.onTokenRegistered()
          SyncId()
        } (Threading.Background)
      }

      Await.ready(service.currentTokenPref.signal.filter(_.contains(token)).head, defaultDuration)
      Await.ready(accountSignal.filter(_.registeredPush.contains(token)).head, defaultDuration)

      val newToken = "newToken"
      service.onTokenRefresh ! newToken

      Await.ready(service.currentTokenPref.signal.filter(_.contains(newToken)).head, defaultDuration)
      Await.ready(accountSignal.filter(_.registeredPush.contains(newToken)).head, defaultDuration)

    }
  }

  feature("Push active") {

    scenario("Push should be active if enabled and inactive if not") {
      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)
      val service = initTokenService()

      pushEnabledPref := true //set active
      googlePlayAvailable ! true

      Await.ready(service.pushActive.filter(_ == true).head, defaultDuration)

      pushEnabledPref := false //set inactive
      Await.ready(service.pushActive.filter(_ == false).head, defaultDuration)
    }

    scenario("Push should be active if in background and inactive if not") {
      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true
      lifecycleActive ! true //websocket should be open

      Await.ready(service.pushActive.filter(_ == false).head, defaultDuration)

      lifecycleActive ! false //websocket should be off - use push again
      Await.ready(service.pushActive.filter(_ == true).head, defaultDuration)
    }

    scenario("Push should be inactive if play services are unavailable") {
      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true

      Await.ready(service.pushActive.filter(_ == true).head, defaultDuration)

      googlePlayAvailable ! false //maybe the user disables play services??
      Await.ready(service.pushActive.filter(_ == false).head, defaultDuration)
    }

  }

  def initTokenService(google:    GoogleApi         = google,
                       prefs:     PreferenceService = prefs,
                       lifecycle: ZmsLifecycle      = lifecycle,
                       accountId: AccountId         = accountId,
                       accounts:  AccountsStorage   = accounts,
                       sync:      SyncServiceHandle = sync) = {
    import PushTokenService._

    (prefs.gcmEnabledKey _).expects().returning(gcmEnabledKey)
    (prefs.uiPreferenceBooleanSignal _).expects(gcmEnabledKey, false).returning(pushEnabledPref)
    (prefs.preference (_: String, _: Option[String])(_: PrefCodec[Option[String]])).expects(*, *, *).anyNumberOfTimes().onCall { (key, default, _) =>
      key match {
        case `pushTokenPrefKey` => pushTokenPref
        case _ => fail(s"Unexpected call to prefs.preference with key: $key")
      }
    }
    (google.isGooglePlayServicesAvailable _).expects().anyNumberOfTimes().returning(googlePlayAvailable)
    (accounts.signal _).expects(*).anyNumberOfTimes().returning(accountSignal)
    (lifecycle.active _).expects().anyNumberOfTimes().returning(lifecycleActive)
    (accounts.update _).expects(accountId, *).anyNumberOfTimes().onCall { (_, f) =>
      Future {
        returning(accountSignal.currentValue("").fold(Option.empty[(AccountData, AccountData)])(p => Some((p, f(p))))) {
          case Some((_, updated)) => accountSignal ! updated
          case _ =>
        }
      }(Threading.Background)
    }

    new PushTokenService(google, prefs, lifecycle, accountId, accounts, sync)
  }
}
