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
import com.waz.utils.returning
import com.waz.utils.wrappers.{GoogleApi, Localytics}

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Responsible for deciding when to generate and register push tokens and whether push should be active at all.
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
  //TODO re-introduce fallback logic from old GcmService
  private val pushEnabled = uiPreferenceBooleanSignal(gcmEnabledKey).signal
  val currentTokenPref    = preference[Option[String]](pushTokenPrefKey, None)
  val onTokenRefresh      = EventStream[String]()

  onTokenRefresh { t => setNewToken(Some(t)) }

  private val shouldGenerateNewToken = for {
    play    <- googleApi.isGooglePlayServicesAvailable
    current <- currentTokenPref.signal
  } yield
    returning(play && current.isEmpty) { gen =>
      verbose(s"Should generate new token: play: $play, $current => $gen")
    }

  shouldGenerateNewToken.on(dispatcher) {
    case true => setNewToken()
    case _    =>
  }

  val pushActive = (for {
    push     <- pushEnabled
    play     <- googleApi.isGooglePlayServicesAvailable
    lcActive <- lifeCycle.active
    if push && play && !lcActive
    current  <- currentTokenPref.signal
  } yield current.isDefined).
    orElse(Signal.const(false))

  val eventProcessingStage = EventScheduler.Stage[GcmTokenRemoveEvent] { (_, events) =>
    currentTokenPref().flatMap {
      case Some(t) if events.exists(_.token == t) =>
        verbose("Clearing all push tokens in response to backend notification")
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

  def onTokenRegistered(): Future[Any] = {
    verbose("onTokenRegistered")
    currentTokenPref().flatMap {
      case Some(token) => accounts.update(accountId, _.copy(registeredPush = Some(token)))
      case value =>
        warn(s"Couldn't find current token after registration - this shouldn't happen, had value: $value")
        Future.successful({})
    }
  }
}

object PushTokenService {
  case class PushSenderId(str: String) extends AnyVal

  //For tests
  val pushTokenPrefKey = "push_token"
}
