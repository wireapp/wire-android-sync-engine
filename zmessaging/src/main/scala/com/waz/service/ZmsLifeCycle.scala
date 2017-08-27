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
import com.waz.ZLog.ImplicitTag._
import com.waz.model.AccountId
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal, SourceSignal}

import scala.concurrent.Future

trait ZmsLifeCycle extends EventContext {

  //There is a logged in account
  def loggedIn: Signal[Boolean]

  //logged in accounts, but no activity and app is in the background
  def idle: Signal[Boolean]

  //At least one account has some background activity going on
  def active: Signal[Boolean]

  //One account is currently logged-in and in the foreground
  //TODO - should UI be active if there are no accounts set to active? i.e. AppEntry?
  def uiActive: Signal[Boolean]

  def accLoggedIn(accountId: AccountId): Signal[Boolean]

  //The account is active and currently being displayed in the UI
  def accInForeground(accountId: AccountId): Signal[Boolean]

  def setLoggedIn(accs: Set[AccountId]): Unit
  def setActiveAccount(acc: Option[AccountId]): Unit

  def acquireSync(source: String = ""): Unit
  def acquirePush(source: String = ""): Unit
  def acquireUi(source: String = ""): Unit

  def releaseSync(source: String = ""): Unit
  def releasePush(source: String = ""): Unit
  def releaseUi(source: String = ""): Unit

  def withSync[Result](body: => Future[Result])(implicit source: LogTag): Future[Result]
  def withPush[Result](body: => Future[Result])(implicit source: LogTag): Future[Result]
  def withUi[Result](body: => Future[Result])(implicit source: LogTag): Future[Result]
}

object ZmsLifeCycle {
  trait LifeCycleState {
    val loggedIn: Set[AccountId]
  }

  object LifeCycleState {
    def apply(active: Option[AccountId], loggedIn: Set[AccountId], uiCount: Int, pushCount: Int): LifeCycleState = {
      if (loggedIn.isEmpty && active.isEmpty) Stopped: LifeCycleState
      else if (uiCount > 0) active.map(id => UiActive(id, loggedIn)).getOrElse(Active(loggedIn))
      else if (pushCount > 0) Active(loggedIn)
      else Idle(loggedIn)
    }
  }

  //No logged in accounts
  case object Stopped extends LifeCycleState {
    override val loggedIn = Set.empty[AccountId]
  }

  //Logged in accounts, but nothing to be done
  case class  Idle(loggedIn: Set[AccountId]) extends LifeCycleState

  //Logged in accounts, but the app can be running in the background. In this state, there may be an active account,
  //but since that account is not being displayed, it should have no preference to the others.
  //Note, this state will also represent the case where the UI is active, but no one of the current accounts is being displayed
  //(e.g., when switching accounts)
  case class  Active(loggedIn: Set[AccountId]) extends LifeCycleState

  //Logged in accounts, and one of them is currently being displayed in the UI
  case class  UiActive(active: AccountId, loggedIn: Set[AccountId]) extends LifeCycleState

}

class ZmsLifeCycleImpl extends ZmsLifeCycle {
  import ZmsLifeCycle._

  private var _loggedInAccounts = Set.empty[AccountId]
  private var _activeAccount    = Option.empty[AccountId]
  private var syncCount = 0
  private var pushCount = 0
  private var uiCount = 0

  private val lifeCycleState = Signal(Stopped: LifeCycleState).disableAutowiring()
  lifeCycleState(state => verbose(s"lifecycle state: $state"))(this)

  override def loggedIn = lifeCycleState.map(_ != Stopped)

  override def uiActive = lifeCycleState.map {
    case UiActive(_, _) => true
    case _              => false
  }

  override def idle = lifeCycleState.map {
    case Idle(_) => true
    case _       => false
  }

  override def active = lifeCycleState.map {
    case UiActive(_, _) | Active(_) => true
    case _ => false
  }

  override def accLoggedIn(accountId: AccountId) = lifeCycleState.map(_.loggedIn.contains(accountId))

  override def accInForeground(accountId: AccountId) = lifeCycleState.map {
    case UiActive(id, _) if id == accountId => true
    case _                                  => false
  }

  override def setLoggedIn(accs: Set[AccountId]) = {
    Threading.assertUiThread()
    _loggedInAccounts = accs
    updateState()
  }

  override def setActiveAccount(accountId: Option[AccountId]) = {
    Threading.assertUiThread()
    _activeAccount = accountId
    updateState()
  }

  def acquireSync(source: String = ""): Unit = acquire('sync, syncCount += 1, source)
  def acquirePush(source: String = ""): Unit = acquire('push, pushCount += 1, source)
  def acquireUi(source: String = ""): Unit = acquire('ui, uiCount += 1, source)

  private def acquire(name: Symbol, action: => Unit, source: String): Unit = {
    Threading.assertUiThread()
    action
    if ((syncCount + pushCount + uiCount) == 1) onContextStart()
    updateState()
    debug(s"acquire${name.name.capitalize}, syncCount: $syncCount, pushCount: $pushCount, uiCount: $uiCount, source: '$source'")
  }

  def releaseSync(source: String = ""): Unit = release('sync, syncCount > 0, syncCount -= 1, source)
  def releasePush(source: String = ""): Unit = release('push, pushCount > 0, pushCount -= 1, source)
  def releaseUi(source: String = ""): Unit = release('ui, uiCount > 0, uiCount -= 1, source)

  def withSync[Result](body: => Future[Result])(implicit source: LogTag = ""): Future[Result] =
    withStateCounter(acquireSync(source), releaseSync(source))(body)

  def withPush[Result](body: => Future[Result])(implicit source: LogTag = ""): Future[Result] =
    withStateCounter(acquirePush(source), releasePush(source))(body)

  def withUi[Result](body: => Future[Result])(implicit source: LogTag = ""): Future[Result] =
    withStateCounter(acquireUi(source), releaseUi(source))(body)

  private def withStateCounter[Result](acquire: => Unit, release: => Unit)(body: => Future[Result]): Future[Result] = {
    import Threading.Implicits.Background
    for {
      _ <- Future(acquire)(Threading.Ui)
      result <- body
      _ <- Future(release)(Threading.Ui)
    } yield result
  }

  private def release(name: Symbol, predicate: => Boolean, action: => Unit, source: String): Unit = {
    Threading.assertUiThread()
    val id = name.name.capitalize
    assert(predicate, s"release$id should be called exactly once for each acquire$id")
    action
    updateState()
    if ((syncCount + pushCount + uiCount) == 0) onContextStop()
    debug(s"release$id syncCount: $syncCount, pushCount: $pushCount, uiCount: $uiCount, source: '$source'")
  }

  private def updateState() = lifeCycleState ! LifeCycleState(_activeAccount, _loggedInAccounts, uiCount, pushCount)
}
