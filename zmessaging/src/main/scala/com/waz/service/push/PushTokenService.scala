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

import com.waz.HockeyApp
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.{verbose, warn}
import com.waz.content.AccountsStorage
import com.waz.model.{AccountId, GcmTokenRemoveEvent}
import com.waz.service.{EventScheduler, PreferenceService, ZmsLifecycle}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.utils.wrappers.{GoogleApi, Localytics}
import com.waz.utils.{ExponentialBackoff, returning, _}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Responsible for deciding when to generate and register push tokens and whether they should be active at all.
  */
class PushTokenService(googleApi: GoogleApi,
                       prefs:     PreferenceService,
                       lifeCycle: ZmsLifecycle,
                       accountId: AccountId,
                       accounts:  AccountsStorage,
                       sync:      SyncServiceHandle) {

  import PushTokenService._
  implicit val dispatcher = new SerialDispatchQueue(name = "PushTokenDispatchQueue")

  private implicit val ev = EventContext.Global

  import prefs._

  private val pushEnabled = uiPreferenceBooleanSignal(gcmEnabledKey).signal
  val currentTokenPref    = preference[Option[String]](pushTokenPrefKey, None)
  val onTokenRefresh      = EventStream[String]()

  val lastReceivedConvEventTime = prefs.preference[Option[Instant]](lastReceivedKey,     None)
  val lastFetchedConvEventTime  = prefs.preference[Option[Instant]](lastFetchedKey,      None)
  val lastFetchedLocalTime      = prefs.preference[Option[Instant]](lastFetchedLocalKey, None)
  val lastRegistrationTime      = prefs.preference[Option[Instant]](lastRegisteredKey,   None)
  val tokenFailCount            = prefs.preference[Int]            (failCountKey,        0)
  val lastTokenFail             = prefs.preference[Option[Instant]](failedTimeKey,       None)

  onTokenRefresh { t => setNewToken(Some(t)) }

  private val tokenState = for {
    lastReceived   <- lastReceivedConvEventTime.signal
    lastFetched    <- lastFetchedConvEventTime.signal
    localFetchTime <- lastFetchedLocalTime.signal
    lastRegistered <- lastRegistrationTime.signal
    failedCount    <- tokenFailCount.signal
    lastFailed     <- lastTokenFail.signal
    currentToken   <- currentTokenPref.signal
    userRegistered <- accounts.signal(accountId)
      .map(_.registeredPush)
      .map(t => currentToken.isDefined && t == currentToken)
      .orElse(Signal.const(false))
  } yield TokenState(lastReceived, lastFetched, localFetchTime, lastRegistered, failedCount, lastFailed, userRegistered)

  tokenState.map(_.receivingNotifications).on(dispatcher) {
    case true => tokenFailCount := 0
    case _ =>
  }

  private val shouldGenerateNewToken = for {
    play          <- googleApi.isGooglePlayServicesAvailable
    current       <- currentTokenPref.signal
    limitExceeded <- tokenState.map(_.failLimitExceeded)
  } yield (play && (current.isEmpty || limitExceeded), limitExceeded)

  shouldGenerateNewToken.on(dispatcher) {
    case (true, limitExceeded) =>
      verbose(s"Should generate new token. Fail limit exceeded?: $limitExceeded")
      if (limitExceeded)
        for {
          _ <- tokenFailCount.mutate(_ + 1)
          _ <- lastTokenFail := Some(Instant.now)
          _ <- setNewToken()
        } yield {}
      else setNewToken()
    case _ =>
      verbose("Shouldn't generate new token")
  }

  val pushActive = (for {
    push     <- pushEnabled                             if push
    play     <- googleApi.isGooglePlayServicesAvailable if play
    lcActive <- lifeCycle.active                        if !lcActive
    //token state will be undefined at the start, in which case we SHOULD use the token (hence state.forall)
    state    <- tokenState                              if state.shouldUseToken
    current  <- currentTokenPref.signal
  } yield current.isDefined).
    orElse(Signal.const(false))

  val eventProcessingStage = EventScheduler.Stage[GcmTokenRemoveEvent] { (_, events) =>
    currentTokenPref().flatMap {
      case Some(t) if events.exists(_.token == t) =>
        verbose("Clearing all push tokens in response to backend event")
        googleApi.deleteAllPushTokens()
        currentTokenPref := None
      case _ => Future.successful({})
    }
  }

  private def setNewToken(token: Option[String] = None): Future[Unit] = try {
    val t = token.orElse(Some(googleApi.getPushToken))
    t.foreach { t =>
      Localytics.setPushDisabled(false)
      Localytics.setPushRegistrationId(t)
    }
    verbose(s"Setting new push token: $t")
    currentTokenPref := t
  } catch {
    case NonFatal(ex) => Future.successful {
      HockeyApp.saveException(ex, s"unable to set push token")
    }
  }

  private val shouldRegister = for {
    userToken   <- accounts.signal(accountId).map(_.registeredPush)
    globalToken <- currentTokenPref.signal
  } yield
    returning(globalToken.isDefined && userToken != globalToken) { reg =>
      verbose(s"Should register: user: $userToken, global: $globalToken => $reg")
    }

  //TODO figure out why exactly...
  //on dispatcher prevents infinite register loop
  shouldRegister.on(dispatcher) {
    case true => sync.registerPush()
    case false =>
  }

  def onTokenRegistered(): Future[Unit] = {
    verbose("onTokenRegistered")
    currentTokenPref().flatMap {
      case Some(token) =>
        accounts.update(accountId, _.copy(registeredPush = Some(token)))
          .flatMap(_ => lastRegistrationTime := Some(Instant.now))
      case value =>
        warn(s"Couldn't find current token after registration - this shouldn't happen, had value: $value")
        Future.successful({})
    }
  }
}

object PushTokenService {
  case class PushSenderId(str: String) extends AnyVal

  //For tests
  val pushTokenPrefKey    = "push_token"
  val lastReceivedKey     = "last_received_conv_event_time"
  val lastFetchedKey      = "last_fetched_conv_event_time"
  val lastFetchedLocalKey = "last_fetched_local_time"
  val lastRegisteredKey   = "token_registration_time"
  val failCountKey        = "token_failure_count"
  val failedTimeKey       = "last_token_failed_time"

  /**
    * To prevent over-aggressive re-registering of push tokens. The connection to the Google GCM servers can be down for up to 28
    * minutes before the system realises it needs to re-establish the connection. If we miss a message in this time, and the user
    * opens the app, we'll incorrectly diagnose this as a bad token and try to re-register it. So we'll give it a few chances.
    */
  val failLimit = 3

  import scala.concurrent.duration._

  val RegistrationRetryBackoff = new ExponentialBackoff(5.minutes, 30.days)

  /**
    * Current push state, isActive returns true if we should be receiving notifications on it, given native push (FCM) is available
    * We are only comparing timestamps of conversation events, considering events received on native push with those fetched from the notification stream.
    * Events are fetched only when web socket connects (meaning native push was considered active), this means that this state should rarely change.
    */
  case class TokenState(lastReceivedConvEvent: Option[Instant],
                        lastFetchedConvEvent:  Option[Instant],
                        lastFetchedLocalTime:  Option[Instant],
                        lastRegisteredTime:    Option[Instant],
                        failureCount:          Int,
                        lastFailed:            Option[Instant],
                        isUserRegistered:      Boolean) {

    //we received notifications on native push when websockets/fetching were not active (as expected)
    val receivingNotifications = (lastFetchedConvEvent, lastReceivedConvEvent) match {
      case (Some(fetched), Some(received)) => fetched <= received
      case _ => true
    }

    //The token was registered with the current user since the last time we fetched from the notifications stream. Give it a chance to work.
    private val justRegistered = (lastFetchedLocalTime, lastRegisteredTime) match {
      case (Some(fetch), Some(registered)) => fetch <= registered
      case _ => false
    }

    //If the current token doesn't seem to be working, wait a little while before we try using it again.
    private def isBackoffExceeded = lastRegisteredTime.forall(RegistrationRetryBackoff.delay(failureCount).elapsedSince(_))

    /**
      * If the current user isn't registered or just recently registered, or if it's receiving normally, OR if none of the above are true
      * and the backoff has gone by, we should be using (or trying to use) native push.
      */
    def shouldUseToken = !isUserRegistered || justRegistered || receivingNotifications || isBackoffExceeded

    private val justFailed = (lastFailed, lastRegisteredTime) match {
      case (Some(failed), Some(registered)) => registered <= failed
      case _ => false
    }

    val failLimitExceeded = failureCount >= failLimit && justFailed
  }

}
