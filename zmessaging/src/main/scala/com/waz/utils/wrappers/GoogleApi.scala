package com.waz.utils.wrappers

import android.app.Activity
import com.google.android.gms.common.{ConnectionResult, GoogleApiAvailability}
import com.google.firebase.iid.FirebaseInstanceId
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.warn
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.GoogleApi.requestGooglePlayServices
import com.waz.utils.{LoggedTry, returning}

trait GoogleApi {
  def isGooglePlayServicesAvailable: Signal[Boolean]
  def checkGooglePlayServicesAvailable(activity: Activity): Unit
  def onActivityResult(requestCode: Int, resultCode: Int)
  def getPushToken: String
  def deleteAllPushTokens(): Unit
}

class GoogleApiImpl extends GoogleApi {

  private lazy val api = GoogleApiAvailability.getInstance()

  override val isGooglePlayServicesAvailable = Signal[Boolean](false)

  override def checkGooglePlayServicesAvailable(activity: Activity) = api.isGooglePlayServicesAvailable(activity) match {
    case ConnectionResult.SUCCESS => isGooglePlayServicesAvailable ! true
    case code if api.isUserResolvableError(code) => api.getErrorDialog(activity, code, requestGooglePlayServices)
    case code => warn(s"Google Play Services not available: error code: $code")
  }

  override def onActivityResult(requestCode: Int, resultCode: Int) = requestCode match {
    case `requestGooglePlayServices` =>
      resultCode match {
        case Activity.RESULT_OK => isGooglePlayServicesAvailable ! true
        case _ => warn("Failed to update/install Google Play Services")
      }
    case _ => //
  }

  override def getPushToken =
    returning(FirebaseInstanceId.getInstance().getToken) { t =>
      if (t == null) throw new Exception("No FCM token was returned from the FirebaseInstanceId")
    }

  override def deleteAllPushTokens(): Unit =
    LoggedTry.local { FirebaseInstanceId.getInstance().deleteInstanceId() }
}

object GoogleApi {
  val requestGooglePlayServices = 7976
}
