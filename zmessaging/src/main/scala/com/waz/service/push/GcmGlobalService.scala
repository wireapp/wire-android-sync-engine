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

import android.content.{Context, SharedPreferences}
import com.google.android.gms.common.{ConnectionResult, GooglePlayServicesUtil}
import com.google.android.gms.gcm.GoogleCloudMessaging
import com.google.android.gms.iid.InstanceID
import com.localytics.android.Localytics
import com.waz.HockeyApp
import com.waz.HockeyApp.NoReporting
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.model._
import com.waz.service.push.GcmGlobalService.{GcmRegistration, GcmSenderId}
import com.waz.service.{BackendConfig, MetaDataService, PreferenceServiceImpl}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.LoggedTry
import com.waz.utils.events.EventContext

import scala.util.control.{NoStackTrace, NonFatal}

class GcmGlobalService(context: Context, val prefs: PreferenceServiceImpl, metadata: MetaDataService, backendConfig: BackendConfig) {

  implicit val dispatcher = new SerialDispatchQueue(name = "GcmGlobalDispatchQueue")

  private implicit val ev = EventContext.Global

  import metadata._
  import prefs._

  val gcmSenderId: GcmSenderId = backendConfig.gcmSenderId

  lazy val gcmCheckResult = try GooglePlayServicesUtil.isGooglePlayServicesAvailable(context) catch {
    case ex: Throwable =>
      error(s"GooglePlayServices availability check failed", ex)
      ConnectionResult.DEVELOPER_ERROR
  }

  val gcmEnabled = prefs.uiPreferenceBooleanSignal(prefs.gcmEnabledKey).signal

  def gcmAvailable = prefs.gcmEnabled && gcmCheckResult == ConnectionResult.SUCCESS

  def getGcmRegistration: CancellableFuture[GcmRegistration] = withPreferences(GcmRegistration(_)) map { reg =>
    if (reg.version == appVersion) reg
    else GcmRegistration("", AccountId(""), appVersion)
  }

  def clearGcm(user: AccountId) = withPreferences { prefs =>
    val reg = GcmRegistration(prefs)
    verbose(s"clearGcmRegistrationUser($user): $reg")
    if (reg.user == user) {
      val edit = prefs.edit()
      reg.copy(user = AccountId("")).save(edit)
      edit.commit()
    }
  }

  //removes the current gcm token and generates a new one - ensures that the user shouldn't be left without a GCM token
  def resetGcm(user: AccountId): CancellableFuture[Option[GcmRegistration]] = CancellableFuture.lift(unregister()) flatMap { _ =>
    withGcm {
      LoggedTry {deleteInstanceId()} // if localytics registered first with only their sender id, we have to unregister so that our own additional sender id gets registered, too
      try {
        val token = getGcmToken(gcmSenderId.str +: metadata.localyticsSenderId.toSeq)
        Localytics.setPushDisabled(false)
        Localytics.setPushRegistrationId(token)
        CancellableFuture.successful(Some(setGcm(token, AccountId(""))))
      } catch {
        case NonFatal(ex) =>
          setGcm("", AccountId(""))
          warn(s"registerGcm failed for sender: '$gcmSenderId'", ex)
          HockeyApp.saveException(ex, s"unable to register gcm for sender $gcmSenderId")
          CancellableFuture.successful(None)
      }
    }
  }

  private def setGcm(token: String, user: AccountId): GcmRegistration = {
    val reg = GcmRegistration(token, user, appVersion)
    verbose(s"setGcmRegistration: $reg")
    editPreferences(reg.save(_))
    reg
  }

  //used to indicate that the token was registered properly with the BE - no user indicates it's not registered
  def updateRegisteredUser(token: String, user: AccountId) = withPreferences { prefs =>
    val reg = GcmRegistration(prefs)
    if (reg.token == token && reg.user != user) {
      val updated = reg.copy(user = user, version = appVersion)
      val editor = prefs.edit()
      updated.save(editor)
      editor.commit()
      updated
    } else reg
  }

  def unregister() = editPreferences(GcmRegistration().save(_)).future map { _ => deleteInstanceId() } recover { case NonFatal(e) => warn("unable to unregister from GCM", e) }

  private def withGcm[A](body: => A): A = if (gcmAvailable) body else throw new GcmGlobalService.GcmNotAvailableException

  private def getGcmToken(senderIds: Seq[String]) =
     InstanceID.getInstance(context).getToken(senderIds mkString ",", GoogleCloudMessaging.INSTANCE_ID_SCOPE)

  //Deleting the instance id also removes any tokens the instance id was using
  private def deleteInstanceId(): Unit = LoggedTry.local { InstanceID.getInstance(context).deleteInstanceID() }
}

object GcmGlobalService {

  case class GcmSenderId(str: String) extends AnyVal

  val RegistrationIdPref = "registration_id"
  val RegistrationUserPref = "registration_user"
  val RegistrationVersionPref = "registration_version"

  class GcmNotAvailableException extends Exception("Google Play Services not available") with NoReporting with NoStackTrace

  case class GcmRegistration(token: String = "", user: AccountId = AccountId(""), version: Int = 0) {
    def save(editor: SharedPreferences.Editor) = {
      editor.putString(RegistrationIdPref, token)
      editor.putString(RegistrationUserPref, user.str)
      editor.putInt(RegistrationVersionPref, version)
    }
  }

  object GcmRegistration {
    def apply(prefs: SharedPreferences): GcmRegistration =
      GcmRegistration(prefs.getString(RegistrationIdPref, ""), AccountId(prefs.getString(RegistrationUserPref, "")), prefs.getInt(RegistrationVersionPref, 0))
  }
}
