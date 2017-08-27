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
import com.waz.content.GlobalPreferences.{CurrentAccountPref, PushEnabledKey}
import com.waz.content.{AccountsStorage, GlobalPreferences}
import com.waz.model.{AccountId, PushToken, PushTokenRemoveEvent}
import com.waz.service.{EventScheduler, ZmsLifeCycle}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.returning
import com.waz.utils.wrappers.{GoogleApi, Localytics}

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Responsible for deciding when to generate and register push tokens and whether they should be active at all.
  */
class PushTokenService(googleApi:    GoogleApi,
                       globalToken:  GlobalTokenService,
                       prefs:        GlobalPreferences,
                       lifeCycle:    ZmsLifeCycle,
                       accountId:    AccountId,
                       loggedInAccs: Signal[Set[AccountId]],
                       accStorage:   AccountsStorage,
                       sync:         SyncServiceHandle) {

  implicit val dispatcher = globalToken.dispatcher
  implicit val ev = globalToken.ev

  val pushEnabled    = prefs.preference(PushEnabledKey)
  val currentAccount = prefs.preference(CurrentAccountPref)
  val currentToken   = globalToken.currentToken

  //None if user is not logged in.
  private val isLoggedIn = loggedInAccs.map(_.contains(accountId))

  private val userToken = accStorage.signal(accountId).map(_.registeredPush)

  val pushActive = (for {
    push           <- pushEnabled.signal                      if push
    play           <- googleApi.isGooglePlayServicesAvailable if play
    inForeground   <- lifeCycle.accInForeground(accountId)    if !inForeground
    current        <- currentToken.signal                     if current.isDefined
    userRegistered <- userToken.map(_ == current)
  } yield userRegistered)
    .orElse(Signal.const(false))

  val eventProcessingStage = EventScheduler.Stage[PushTokenRemoveEvent] { (_, events) =>
    globalToken.resetGlobalToken(events.map(_.token))
  }

  //on dispatcher prevents infinite register loop
  (for {
    true        <- isLoggedIn
    userToken   <- userToken
    globalToken <- currentToken.signal
  } yield (globalToken, userToken)).on(dispatcher) {
    case (Some(glob), Some(user)) if glob != user =>
      sync.deletePushToken(user)
      sync.registerPush(glob)
    case (Some(glob), None) =>
      sync.registerPush(glob)
    case (None, Some(user)) =>
      sync.deletePushToken(user)
    case _ => //do nothing
  }

  def onTokenRegistered(token: PushToken): Future[Unit] = {
    verbose(s"onTokenRegistered: $accountId, $token")
    (for {
      true <- isLoggedIn.head
      _    <- accStorage.update(accountId, _.copy(registeredPush = Some(token)))
    } yield {}).recover {
      case _ => warn("account was not logged in after token sync completed")
    }
  }
}

class GlobalTokenService(googleApi: GoogleApi, prefs: GlobalPreferences) {

  implicit val dispatcher = new SerialDispatchQueue(name = "PushTokenDispatchQueue")
  implicit val ev = EventContext.Global

  val currentToken = prefs.preference(GlobalPreferences.PushToken)
  private var settingToken = Future.successful({})

  private val shouldGenerateNewToken = for {
    play    <- googleApi.isGooglePlayServicesAvailable
    current <- currentToken.signal
  } yield play && current.isEmpty

  shouldGenerateNewToken {
    case true => setNewToken()
    case _ =>
  }

  //Specify empty to force remove all tokens, or else only remove if `toRemove` contains the current token.
  def resetGlobalToken(toRemove: Vector[PushToken] = Vector.empty) =
    currentToken().flatMap {
      case Some(t) if toRemove.contains(t) || toRemove.isEmpty =>
        verbose("Resetting push token")
        googleApi.deleteAllPushTokens()
        for {
          _ <- currentToken := None
          _ <- setNewToken()
        } yield {}
      case _ => Future.successful({})
    }

  def setNewToken(token: Option[PushToken] = None): Future[Unit] = {
    if (settingToken.isCompleted) {
      settingToken = for {
        t <- dispatcher {
          returning(token.orElse(googleApi.getPushToken)) { t =>
            verbose(s"Setting new push token: $t")
            t.foreach { t =>
              Localytics.setPushDisabled(false)
              Localytics.setPushRegistrationId(t.str)
            }
          }
        }.recover {
          case NonFatal(ex) =>
            HockeyApp.saveException(ex, s"unable to set push token")
            Option.empty[PushToken]
        }.future
        _ <- currentToken := t
      } yield {}
    }
    settingToken
  }
}

object PushTokenService {
  case class PushSenderId(str: String) extends AnyVal
}
