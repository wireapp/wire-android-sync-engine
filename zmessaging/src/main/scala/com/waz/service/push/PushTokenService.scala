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
import com.waz.ZLog.verbose
import com.waz.content.AccountsStorage
import com.waz.model.{AccountId, GcmTokenRemoveEvent}
import com.waz.service.{EventScheduler, PreferenceService, ZmsLifecycle}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.utils.wrappers.{GoogleApi, Localytics}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.util.control.NonFatal

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

  val lastReceivedConvEventTime = preference[Instant]("last_received_conv_event_time", Instant.EPOCH)
  val lastFetchedConvEventTime  = preference[Instant]("last_fetched_conv_event_time",  Instant.ofEpochMilli(1))
  val lastFetchedLocalTime      = preference[Instant]("last_fetched_local_time",       Instant.EPOCH)
  val lastTokenCreatedTime      = preference[Instant]("push_registration_time",        Instant.EPOCH)
  //TODO check to see we don't increase the fail limit if the token hasn't been registered yet... Or maybe registration should be part of this class...
  val lastTokenRegisteredTime   = preference[Instant]("push_registration_time",        Instant.EPOCH)
  val tokenFailedCount          = preference[Int]    ("push_token_failure_count",      0)

  private val pushEnabled = uiPreferenceBooleanSignal(gcmEnabledKey).signal
  val currentTokenPref = preference[Option[String]]("push_token", None)

  val onTokenRefresh = EventStream[String]()

  onTokenRefresh { t => setNewToken(Some(t)) }

  /**
    * Current GCM state, true if we are receiving notifications on it.
    * We are only comparing timestamps of conversation events, considering events received on GCM and fetched by EventsClient.
    * Events are fetched only when web socket connects (meaning GCM was considered active), this means that this state should rarely change.
    *
    * GcmState will be active if the last received event is up-to-date or newer than the last fetched event, OR we have registered our GCM token since the
    * last time we fetched a message (meaning we 'just registered')
    */
  private val pushState = for {
    lastFetched    <- lastFetchedConvEventTime.signal
    lastReceived   <- lastReceivedConvEventTime.signal
    localFetchTime <- lastFetchedLocalTime.signal
    lastCreated    <- lastTokenCreatedTime.signal
  } yield {
    verbose(s"gcmState, lastFetched: $lastFetched, lastReceived: $lastReceived, localFetchTime: $localFetchTime, lastCreated: $lastCreated")
    PushState(lastFetched <= lastReceived, localFetchTime <= lastCreated)
  }

  pushState {
    case PushState(true, _) => tokenFailedCount := 0
    case _ =>
  }

  private val shouldGenerateNewToken = (for {
    play     <- googleApi.isGooglePlayServicesAvailable if play
    state    <- pushState
    time     <- lastTokenCreatedTime.signal
    failures <- tokenFailedCount.signal
    current  <- currentTokenPref.signal
  } yield
    current.isEmpty || (!state.active && RegistrationRetryBackoff.delay(math.max(0, failures - retryFailLimit)).elapsedSince(time))
  ).orElse(Signal.const(false))

  shouldGenerateNewToken {
    case true =>
      for {
        failures <- tokenFailedCount()
        current  <- currentTokenPref.signal
      } {
        if (current.isEmpty || failures > retryFailLimit) {
          verbose("No token or we've exceeded the retry fail limit - setting a new token")
          setNewToken()
        }
        else if (current.isDefined && failures < retryFailLimit) {
          verbose(s"Seems as though we missed messages $failures times in a row - increase the fail count")
          tokenFailedCount.mutate(_ + 1)
        }
      }
    case false =>
      verbose(s"shouldReRegister == false")
  }

  val pushActive = (for {
    push     <- pushEnabled
    play     <- googleApi.isGooglePlayServicesAvailable
    lcActive <- lifeCycle.active
    if push && play && !lcActive
    st    <- pushState
    ct    <- tokenFailedCount.signal
  } yield {
    //If we missed a gcm notification (say because the GCM server connection was down), then the state will be !active
    //but we don't want to turn on the websocket just yet - we would then rely on web-socket and not get any updates to GCM, meaning
    //it would be permanently disabled.
    returning(st.active || ct < retryFailLimit) { active =>
      verbose(s"Should push be active $active")
    }
  }).orElse(Signal.const(false))

  val eventProcessingStage = EventScheduler.Stage[GcmTokenRemoveEvent] { (convId, events) =>
    currentTokenPref().flatMap {
      case Some(t) if events.exists(_.token == t) =>
        verbose("Clearing all push tokens in response to backend notification")
        googleApi.deleteAllPushTokens()
        (currentTokenPref := None).flatMap(_ => setNewToken())
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
    for {
      _ <- currentTokenPref := t
      _ <- tokenFailedCount := 0
      _ <- lastTokenCreatedTime := Instant.now()
    } yield {}
  } catch {
    case NonFatal(ex) => Future.successful {
      HockeyApp.saveException(ex, s"unable to set push token")
    }
  }

  private val shouldRegister = (for {
    userToken         <- accounts.signal(accountId).map(_.registeredPush)
    Some(globalToken) <- currentTokenPref.signal
  } yield {
    userToken.isEmpty || userToken.contains(globalToken)
  }).orElse(Signal.const(false))

  shouldRegister {
    case true => sync.registerPush()
    case false => //
  }

  def onTokenRegistered(): Future[Unit] = {
    currentTokenPref().flatMap {
      case Some(token) =>
        for {
          _ <- accounts.update(accountId, _.copy(registeredPush = Some(token)))
          _ <- lastTokenRegisteredTime := Instant.now()
        } yield {}
      case _ => Future.successful({})
    }
  }
}

object PushTokenService {

  /**
    * To prevent over-aggressive re-registering of GCM tokens. The connection to the Google GCM servers can be down for up to 28
    * minutes before the system realises it needs to re-establish the connection. If we miss a message in this time, and the user
    * opens the app, we'll incorrectly diagnose this as a bad token and try to re-register it. So we'll give it a few chances.
    */
  val retryFailLimit = 2

  import scala.concurrent.duration._

  val RegistrationRetryBackoff = new ExponentialBackoff(5.minutes, 30.days)

  case class PushState(received: Boolean, justRegistered: Boolean) {
    def active = received || justRegistered
  }

  case class PushSenderId(str: String) extends AnyVal
}
