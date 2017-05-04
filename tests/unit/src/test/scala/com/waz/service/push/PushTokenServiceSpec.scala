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
import com.waz.content.Preference.PrefCodec
import com.waz.content.{AccountsStorage, Preference}
import com.waz.model._
import com.waz.service.{PreferenceService, ZmsLifecycle}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.testutils.TestClock
import com.waz.threading.Threading
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.utils.returning
import com.waz.utils.wrappers.GoogleApi
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import org.threeten.bp.Instant
import com.waz.testutils._

import scala.concurrent.Future
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
  val clock     = new TestClock

  var pushEnabledPref:      Preference[Boolean] = _
  var pushTokenPref:        Preference[Option[String]] = _
  var lastReceivedPref:     Preference[Instant] = _
  var lastFetchedPref:      Preference[Instant] = _
  var lastFetchedLocalPref: Preference[Instant] = _
  var lastRegisteredPref:   Preference[Instant] = _
  var failedCountPref:      Preference[Int] = _
  var failedTimePref:       Preference[Instant] = _

  var googlePlayAvailable: SourceSignal[Boolean] = _
  var lifecycleActive: SourceSignal[Boolean] = _
  var accountSignal: SourceSignal[AccountData] = _

  val defaultDuration = 5.seconds

  before {
    clock.reset()
    pushEnabledPref = Preference.inMemory[Boolean](false, if (ZLog.testLogging) Some(gcmEnabledKey) else None)
    pushTokenPref = Preference.inMemory[Option[String]](None, if (ZLog.testLogging) Some(pushTokenPrefKey) else None)

    //TODO - get these default values from the calls in the service itself (dangerous to have to remember to do it here in the tests)
    lastReceivedPref      = Preference.inMemory[Instant](Instant.EPOCH, if (ZLog.testLogging) Some(lastReceivedKey) else None)
    lastFetchedPref       = Preference.inMemory[Instant](Instant.EPOCH, if (ZLog.testLogging) Some(lastFetchedKey) else None)
    lastFetchedLocalPref  = Preference.inMemory[Instant](Instant.EPOCH, if (ZLog.testLogging) Some(lastFetchedLocalKey) else None)
    lastRegisteredPref    = Preference.inMemory[Instant](Instant.EPOCH, if (ZLog.testLogging) Some(lastRegisteredKey) else None)
    failedCountPref       = Preference.inMemory[Int]    (0,             if (ZLog.testLogging) Some(failCountKey) else None)
    failedTimePref        = Preference.inMemory[Instant](Instant.EPOCH, if (ZLog.testLogging) Some(failedTimeKey) else None)

    googlePlayAvailable = Signal(false)
    lifecycleActive = Signal(false)
    accountSignal = Signal[AccountData]()
  }

  feature("Token generation and registration") {
    scenario("Fetches token on init if GCM available") {
      val token = "token"
      (google.getPushToken _).expects().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true
      result(service.currentTokenPref.signal.filter(_.contains(token)).head)
    }

    scenario("Remove Push Token event should create new token and delete all previous tokens") {

      val oldToken = "oldToken"
      val newToken = "newToken"
      var calls = 0
      (google.getPushToken _).expects().anyNumberOfTimes().onCall { () =>
        calls += 1
        calls match {
          case 1 => oldToken
          case 2 => newToken
          case _ => fail("Too many calls to getPushToken!")
        }
      }

      //This needs to be called
      (google.deleteAllPushTokens _).expects().once()

      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true
      //wait for first token to be set
      result(service.currentTokenPref.signal.filter(_.contains(oldToken)).head)
      //delete first token in response to BE event
      service.eventProcessingStage(RConvId(), Vector(GcmTokenRemoveEvent(oldToken, "sender", Some("client"))))
      //new token should be set
      result(service.currentTokenPref.signal.filter(_.contains(newToken)).head)
    }

    scenario("If current user does not have matching registeredPush token, register the user with our BE") {

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

      result(service.currentTokenPref.signal.filter(_.contains(token)).head)
      result(accountSignal.filter(_.registeredPush.contains(token)).head)
    }

    scenario("Instance Id token refresh should trigger re-registration for current user") {
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

      result(service.currentTokenPref.signal.filter(_.contains(token)).head)
      result(accountSignal.filter(_.registeredPush.contains(token)).head)

      val newToken = "newToken"
      service.onTokenRefresh ! newToken //InstanceIDService triggers new token

      result(service.currentTokenPref.signal.filter(_.contains(newToken)).head)
      result(accountSignal.filter(_.registeredPush.contains(newToken)).head)

    }

    scenario("Current: Token that's failed 'failLimit' times should cause push to be inactive, and then only be re-generated and re-registered after the backoff is exceeded") {
      accountSignal ! AccountData(accountId, None, "", None, None, Some("oldToken"))
      pushEnabledPref := true
      googlePlayAvailable ! true
      lifecycleActive ! false

      val oldToken = "oldToken"
      val newToken = "newToken"
      var calls = 0
      (google.getPushToken _).expects().anyNumberOfTimes().onCall { () =>
        println("getPushToken")
        calls += 1
        calls match {
          case 1 => oldToken
          case 2 => newToken
          case _ => fail("Too many calls to getPushToken!")
        }
      }

      lazy val service = initTokenService()

      (sync.registerPush _).expects().anyNumberOfTimes().onCall { () =>
        println("registerPush")
        Future {
          service.onTokenRegistered()
          SyncId()
        } (Threading.Background)
      }

      result(service.currentTokenPref.signal.filter(_.contains(oldToken)).head)
      result(accountSignal.filter(_.registeredPush.contains(oldToken)).head)
      result(service.pushActive.filter(_ == true).head)

      (1 to PushTokenService.failLimit).foreach { i =>
        clock.advance(10.seconds)

        service.lastFetchedConvEventTime := Instant.now(clock)
        service.lastFetchedLocalTime := Instant.now(clock)

        //Wait for failure to update before continuing
        result(service.tokenFailCount.signal.filter(_ == i).head)
        result(service.lastTokenFail.signal.filter(_ == Instant.ofEpochMilli((i * 10).seconds.toMillis)).head)
      }

      waitUntilTasksFinished(service.dispatcher)
      //Check that token hasn't changed or been re-registered
      result(service.pushActive.filter(_ == false).head)
      result(service.currentTokenPref.signal.filter(_.contains(oldToken)).head)
      result(accountSignal.filter(_.registeredPush.contains(oldToken)).head)

      //backoff passes
      clock.advance(RegistrationRetryBackoff.maxDelay)
      service.tokenState.currentValue("").get.backOffClock.check()

      result(service.currentTokenPref.signal.filter(_.contains(newToken)).head)
      result(accountSignal.filter(_.registeredPush.contains(newToken)).head)
      result(service.pushActive.filter(_ == true).head)
    }

    scenario("Non-matching user/global tokens should delete old user token on BE.") {
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

      result(service.pushActive.filter(_ == true).head)

      pushEnabledPref := false //set inactive
      result(service.pushActive.filter(_ == false).head)
    }

    scenario("Push should be active if in background and inactive if not") {
      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true
      lifecycleActive ! true //websocket should be open

      result(service.pushActive.filter(_ == false).head)

      lifecycleActive ! false //websocket should be off - use push again
      result(service.pushActive.filter(_ == true).head)
    }

    scenario("Push should be inactive if play services are unavailable") {
      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)
      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! false

      waitUntilTasksFinished(service.dispatcher)
      result(service.pushActive.filter(_ == false).head)
    }

    scenario("Failed count should increment if we miss notifications while push is active") {
      val token = "token"
      (google.getPushToken _).expects().anyNumberOfTimes().returning(token)

      val service = initTokenService()

      pushEnabledPref := true
      googlePlayAvailable ! true
      lifecycleActive ! false

      //native push should be active
      result(service.pushActive.filter(_ == true).head)

      service.lastFetchedConvEventTime := Instant.now
      service.lastFetchedLocalTime := Instant.now

      result(service.tokenFailCount.signal.filter(_ == 1).head)
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

    //Pretty hacky - but the only thing that works with Scalamock
    (prefs.preference (_: String, _: Any)(_: PrefCodec[Any])).expects(*, *, *).anyNumberOfTimes().onCall((key, _, _) => key match {
        case `pushTokenPrefKey`     => pushTokenPref.asInstanceOf[Preference[Any]]
        case `lastReceivedKey`      => lastReceivedPref.asInstanceOf[Preference[Any]]
        case `lastFetchedKey`       => lastFetchedPref.asInstanceOf[Preference[Any]]
        case `lastFetchedLocalKey`  => lastFetchedLocalPref.asInstanceOf[Preference[Any]]
        case `lastRegisteredKey`    => lastRegisteredPref.asInstanceOf[Preference[Any]]
        case `failedTimeKey`        => failedTimePref.asInstanceOf[Preference[Any]]
        case `failCountKey`         => failedCountPref.asInstanceOf[Preference[Any]]
        case key => fail(s"Unexpected call to prefs.preference with key: $key")
      })
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

    new PushTokenService(google, prefs, lifecycle, accountId, accounts, sync, clock)
  }

  //Allows type parameters in mocking
  def mockPreference[A](prefs: PreferenceService)(onCall: String => Preference[A]) = {
    (prefs.preference[A] (_: String, _: A)(_: PrefCodec[A])).expects(*, *, *).anyNumberOfTimes().onCall((key, _, _) => onCall(key))
  }
}

