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
import com.waz.Analytics
import com.waz.Analytics.NoReporting
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model._
import com.waz.service.push.GcmGlobalService.{GcmRegistration, GcmSenderId}
import com.waz.service.{BackendConfig, MetaDataService, PreferenceService}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.LoggedTry
import com.waz.utils.events.EventContext

import scala.util.control.{NoStackTrace, NonFatal}

class GcmGlobalService(context: Context, prefs: PreferenceService, metadata: MetaDataService, backendConfig: BackendConfig) {

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

  lazy val gcmAvailable = gcmCheckResult == ConnectionResult.SUCCESS

  def getGcmRegistration: CancellableFuture[GcmRegistration] = withPreferences(GcmRegistration(_)) map { reg =>
    if (reg.version == appVersion) reg
    else GcmRegistration("", AccountId(""), appVersion)
  }

  def setGcmRegistration(token: String, user: AccountId): GcmRegistration = {
    val reg = GcmRegistration(token, user, appVersion)
    verbose(s"setGcmRegistration: $reg")
    editPreferences(reg.save(_))
    reg
  }

  def clearGcmRegistrationUser(user: AccountId) = withPreferences { prefs =>
    val reg = GcmRegistration(prefs)
    verbose(s"clearGcmRegistrationUser($user): $reg")
    if (reg.user == user) {
      val edit = prefs.edit()
      reg.copy(user = AccountId("")).save(edit)
      edit.commit()
    }
  }

  def registerGcm(user: AccountId): CancellableFuture[Option[GcmRegistration]] = getGcmRegistration flatMap {
    case reg @ GcmRegistration(token, _, _) if token.nonEmpty =>
      debug(s"registerGcm, already registered: $reg, reusing token")
      CancellableFuture.successful(Some(reg))

    case reg =>
      debug(s"registerGcm($user), registering to play previous: $reg")
      withGcm {
        LoggedTry { unregisterFromGoogle() } // if localytics registered first with only their sender id, we have to unregister so that our own additional sender id gets registered, too
        try {
          val token = registerWithGoogle(gcmSenderId.str +: metadata.localyticsSenderId.toSeq)
          Localytics.setPushDisabled(false)
          Localytics.setPushRegistrationId(token)
          CancellableFuture.successful(Some(setGcmRegistration(token, AccountId(""))))
        } catch {
          case NonFatal(ex) =>
            setGcmRegistration("", AccountId(""))
            warn(s"registerGcm failed for sender: '$gcmSenderId'", ex)
            Analytics.saveException(ex, s"unable to register gcm for sender $gcmSenderId")
            CancellableFuture.successful(None)
        }
        CancellableFuture successful None
      }
  }

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

  def unregister() = editPreferences(GcmRegistration().save(_)).future map { _ => withGcm(unregisterFromGoogle()) } recover { case NonFatal(e) => warn("unable to unregister from GCM", e) }

  private def withGcm[A](body: => A): A = if (gcmAvailable) body else throw new GcmGlobalService.GcmNotAvailableException

  private def registerWithGoogle(senderIds: Seq[String]) =
     InstanceID.getInstance(context).getToken(senderIds mkString ",", GoogleCloudMessaging.INSTANCE_ID_SCOPE)

  private def unregisterFromGoogle(): Unit = LoggedTry.local { InstanceID.getInstance(context).deleteInstanceID() }
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
