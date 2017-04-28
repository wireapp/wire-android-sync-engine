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

import com.waz.content.{AccountsStorage, Preference}
import com.waz.content.Preference.PrefCodec
import com.waz.model.{AccountData, AccountId}
import com.waz.service.{PreferenceService, ZmsLifecycle}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.GoogleApi
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FeatureSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

class PushTokenServiceSpec extends FeatureSpec with AndroidFreeSpec with MockFactory with Matchers {

  val gcmEnabledKey = "PUSH_ENABLED_KEY"

  val google = mock[GoogleApi]
  val prefs = mock[PreferenceService]
  val lifecycle = mock[ZmsLifecycle]
  val accounts = mock[AccountsStorage]
  val accountId = AccountId()
  val sync = mock[SyncServiceHandle]

  val pushEnabledPref = Preference.inMemory[Boolean](false)
  val googlePlayAvailable = Signal(false)
  val lifecycleActive = Signal(false)
  val accountSignal = Signal.empty[AccountData]

  val defaultDuration = 5.seconds

  feature("Token availability") {
    scenario("Fetches token on init if GCM available") {

      val token = "token"
      (google.getPushToken _).expects().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true

      Await.ready(service.currentTokenPref.signal.filter(_.contains(token)).head, defaultDuration)
      Await.ready(service.tokenFailedCount.signal.filter(_ == 0).head, defaultDuration)
    }

    scenario("Fetches new token if failure count exceeds retry limit") {
      val token = "token"
      (google.getPushToken _).expects().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true

      Await.ready(service.currentTokenPref.signal.filter(_.contains(token)).head, defaultDuration)
    }


    scenario("Remove Push Token event should create new token and reset retry count") {
      fail()
    }

    scenario("If play services is not available, we should never query for a new token") {
      fail()
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
    mockPreference(prefs)
    (prefs.gcmEnabledKey _).expects().returning(gcmEnabledKey)
    (prefs.uiPreferenceBooleanSignal _).expects(gcmEnabledKey, false).returning(pushEnabledPref)
    (google.isGooglePlayServicesAvailable _).expects().anyNumberOfTimes().returning(googlePlayAvailable)
    (accounts.signal _).expects(*).anyNumberOfTimes().returning(accountSignal)
    (lifecycle.active _).expects().anyNumberOfTimes().returning(lifecycleActive)

    new PushTokenService(google, prefs, lifecycle, accountId, accounts, sync)
  }

  def mockPreference[A](mockPrefs: PreferenceService) = {
    (mockPrefs.preference (_: String, _: A)(_: PrefCodec[A])).expects(*, *, *).anyNumberOfTimes().onCall { (_, default, _) =>
      Preference.inMemory[A](default)
    }
  }

}
