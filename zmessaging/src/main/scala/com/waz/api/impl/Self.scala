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
package com.waz.api.impl

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api
import com.waz.api._
import com.waz.api.impl.otr.OtrClients
import com.waz.model._
import com.waz.model.otr.Client
import com.waz.service.AccountManager
import com.waz.threading.Threading
import com.waz.ui.{SignalLoading, UiModule}
import com.waz.utils.events.Signal

import scala.concurrent.Future
import scala.util.{Failure, Success}

class Self()(implicit ui: UiModule) extends com.waz.api.Self with UiObservable with SignalLoading {

  var data = Option.empty[AccountData]

  private var upToDate = true

  private def users = ui.users

  private var user = Option.empty[User]

  private val userUpdateListener = new UpdateListener {
    override def updated(): Unit = {
      verbose(s"self user changed: $user")
      notifyChanged()
    }
  }

  def signal(acc: Option[AccountManager]): Signal[(Option[AccountData], Boolean)] = acc match {
    case None    => ui.global.blacklist.upToDate map { (Option.empty[AccountData], _) }
    case Some(a) => a.accountData.map(Option(_)).zip(a.global.blacklist.upToDate)
  }

  accountLoaderOpt(signal) { case (account, upToDate) =>
    debug(s"onLoaded: ${(account, upToDate)}")
    update(account)
    if (this.upToDate != upToDate) {
      this.upToDate = upToDate
      notifyChanged()
    }
  }

  def update(acc: Option[AccountData]): Unit = {
    verbose(s"update($acc)")
    val previousState = (data, user)
    this.data = acc
    if (user.map(_.data.id) != userId) {
      user.foreach(_.removeUpdateListener(userUpdateListener))
      user = userId.map(users.getUser)
      user.foreach(_.addUpdateListener(userUpdateListener))
    }
    if (previousState != (data, user)) notifyChanged()
  }

  def userId = data.flatMap(_.userId)

  override def getOtherOtrClients = userId.fold[CoreList[OtrClient]](OtrClients.Empty) { OtrClients(_, skipSelf = true) }

  override def getIncomingOtrClients = OtrClients.incoming

  override def getOtrClient: api.UiSignal[api.OtrClient] =
    UiSignal.mapped(
      zms => Signal(zms.users.selfUser, zms.otrClientsService.selfClient),
      { p: (UserData, Client) => new otr.OtrClient(p._1.id, p._2.id, p._2) }
    )

  override def isLoggedIn = data.isDefined

  override def accountActivated: Boolean = data.exists(d => d.phone.isDefined || d.email.isDefined)

  @deprecated("use accountActivated instead", "73")
  override def isEmailVerified: Boolean = data.exists(d => d.email.isDefined)

  @deprecated("this method always returns true", "68")
  override def isPhoneVerified: Boolean = data.exists(d => d.phone.isDefined)

  override def isUpToDate: Boolean = upToDate

  override def resendVerificationEmail(email: String): Unit = users.requestVerificationEmail(EmailAddress(email))

  override def getUser: api.User = user.orNull

  override def getName: String = user.fold("")(_.getName)

  override def getAccent: AccentColor = user.fold(AccentColors.defaultColor)(_.getAccent)

  override def getEmail: String = data.flatMap(_.email).fold("")(_.str)

  override def getPhone: String = data.flatMap(_.phone).fold("")(_.str)

  override def getPicture = user.fold[api.ImageAsset](ImageAsset.Empty)(_.getPicture)

  override def setAccent(color: api.AccentColor): Unit = users.setSelfColor(AccentColor(color), user)

  override def setName(name: String): Unit = users.setSelfName(name, user)

  override def setPicture(image: api.ImageAsset): Unit = users.setSelfPicture(image)

  override def clearPicture(): Unit = users.clearSelfPicture()

  override def setEmail(email: String, listener: CredentialsUpdateListener): Unit = handlingErrors(users.setSelfEmail(EmailAddress(email)), listener)
  override def setPhone(phone: String, listener: CredentialsUpdateListener): Unit = handlingErrors(users.setSelfPhone(PhoneNumber(phone)), listener)
  override def updatePassword(newPassword: String, currentPassword: String, listener: CredentialsUpdateListener): Unit = handlingErrors(users.updatePassword(newPassword, Option(currentPassword)), listener)
  override def setPassword(password: String, listener: CredentialsUpdateListener): Unit = handlingErrors(users.updatePassword(password, None), listener)

  override def deleteAccount(): Unit = ui.zms.flatMapFuture(_.users.deleteAccount())

  override def getUsername: String = user.fold("")(_.getUsername)

  override def setUsername(username: String, listener: CredentialsUpdateListener) =  handlingErrors(users.setSelfHandle(Handle(username), user), new CredentialsUpdateListener {
    override def onUpdateFailed(code: Int, message: LogTag, label: LogTag): Unit = listener.onUpdateFailed(code, message, label)
    override def onUpdated(): Unit = {
      user.foreach(_.update(handle = Some(Handle(username))))
      ui.zms(_.users.syncSelfNow)
      listener.onUpdated()
    }
  })
  override def hasSetUsername: Boolean = user.fold(false)(_.getUsername.length > 0)

  private def handlingErrors[T](request: Future[Either[ErrorResponse, Unit]], listener: CredentialsUpdateListener): Unit = request.onComplete {
    case Success(Right(())) => listener.onUpdated()
    case Success(Left(ErrorResponse(code, message, label))) => listener.onUpdateFailed(code, message, label)
    case Failure(ex) => listener.onUpdateFailed(499, ex.getMessage, "")
  } (Threading.Ui)

  override def clearEmail(listener: CredentialsUpdateListener): Unit = handlingErrors(users.clearSelfEmail(), new CredentialsUpdateListener {
    override def onUpdateFailed(code: Int, message: LogTag, label: LogTag): Unit = listener.onUpdateFailed(code, message, label)
    override def onUpdated(): Unit = {
      user.foreach(_.update(email = None))
      ui.zms(_.users.syncSelfNow)
      listener.onUpdated()
    }
  })

  override def clearPhone(listener: CredentialsUpdateListener): Unit = handlingErrors(users.clearSelfPhone(), new CredentialsUpdateListener {
    override def onUpdateFailed(code: Int, message: LogTag, label: LogTag): Unit = listener.onUpdateFailed(code, message, label)
    override def onUpdated(): Unit = {
      user.foreach(_.update(phone = None))
      ui.zms(_.users.syncSelfNow)
      listener.onUpdated()
    }
  })

  override def isTeamAccount: Boolean = data.exists(_.isTeamAccount)

}
