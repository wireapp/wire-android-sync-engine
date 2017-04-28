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

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.{Context, SharedPreferences}
import android.os.Looper
import com.waz.api.ZmsVersion
import com.waz.content.Preference
import com.waz.content.Preference.PrefCodec
import com.waz.service.PreferenceService.Pref
import com.waz.threading.{CancellableFuture, SerialDispatchQueue, Threading}
import com.waz.utils.events.SourceSignal
import com.waz.zms.R

import scala.concurrent.Future
import scala.util.Try

trait PreferenceService {

  def preferences: SharedPreferences

  def uiPreferences: SharedPreferences

  def withPreferences[A](body: SharedPreferences => A, prefs: => SharedPreferences = preferences): CancellableFuture[A]

  def editPreferences(body: SharedPreferences.Editor => Unit, prefs: => SharedPreferences = preferences): CancellableFuture[Boolean]

  def preference[A](key: String, defaultValue: A)(implicit codec: PrefCodec[A]): Preference[A] = {
    val prefs = preferences
    new Pref[A](prefs, key, Option(prefs.getString(key, null)).fold(defaultValue)(codec.decode), { (prefs, value) => prefs.putString(key, codec.encode(value)) })
  }

  def preference[A](key: String, defaultValue: A, prefs: => SharedPreferences = preferences)(implicit codec: PrefCodec[A]): Preference[A] =
    new Pref[A](prefs, key, Option(prefs.getString(key, null)).fold(defaultValue)(codec.decode), { (prefs, value) => prefs.putString(key, codec.encode(value)) })

  def intPreference(key: String, prefs: => SharedPreferences = preferences, defaultValue: Int = 0): Preference[Int] =
    new Pref[Int](prefs, key, prefs.getInt(key, defaultValue), _.putInt(key, _))

  def longPreference(key: String, prefs: => SharedPreferences = preferences, defaultValue: Long = 0): Preference[Long] =
    new Pref[Long](prefs, key, prefs.getLong(key, defaultValue), _.putLong(key, _))

  def preferenceStringSignal(key: String, prefs: => SharedPreferences = preferences, defaultValue: String = ""): Preference[String] =
    new Pref[String](prefs, key, prefs.getString(key, defaultValue), _.putString(key, _))

  def preferenceBooleanSignal(key: String, prefs: => SharedPreferences = preferences, defaultValue: Boolean = false): Preference[Boolean] =
    new Pref[Boolean](prefs, key, prefs.getBoolean(key, defaultValue), _.putBoolean(key, _))

  def withUiPreferences[A](body: SharedPreferences => A) =
    withPreferences(body, uiPreferences)

  def editUiPreferences(body: SharedPreferences.Editor => Unit) =
    editPreferences(body, uiPreferences)

  def uiPreferenceStringSignal(key: String, defaultValue: String = "") = preferenceStringSignal(key, uiPreferences, defaultValue)
  def uiPreferenceBooleanSignal(key: String, defaultValue: Boolean = false) = preferenceBooleanSignal(key, uiPreferences, defaultValue)

  def autoAnswerCallPrefKey: String
  def callingV3Key: String
  def gcmEnabledKey: String
  def v31AssetsEnabledKey: String
  def wsForegroundKey: String

}

class PreferenceServiceImpl(context: Context) extends PreferenceService {
  import com.waz.service.PreferenceService._

  private implicit val dispatcher = preferenceDispatcher

  lazy val analyticsEnabledPrefKey = Try(context.getResources.getString(R.string.zms_analytics_preference_key)).getOrElse("PREF_KEY_AVS_METRICS")
  lazy val analyticsEnabledPref = uiPreferenceBooleanSignal(analyticsEnabledPrefKey)

  override lazy val autoAnswerCallPrefKey    = Try(context.getResources.getString(R.string.zms_auto_answer_key)).getOrElse("PREF_KEY_AUTO_ANSWER_ENABLED")
  override lazy val callingV3Key             = Try(context.getResources.getString(R.string.zms_calling_v3)).getOrElse("PREF_KEY_CALLING_V3")
  override lazy val gcmEnabledKey            = Try(context.getResources.getString(R.string.zms_gcm_enabled)).getOrElse("PREF_KEY_GCM_ENABLED")
  override lazy val v31AssetsEnabledKey      = Try(context.getResources.getString(R.string.zms_v31_assets_enabled)).getOrElse("PREF_V31_ASSETS_ENABLED")
  override lazy val wsForegroundKey          = Try(context.getResources.getString(R.string.zms_ws_foreground_service_enabled)).getOrElse("PREF_KEY_WS_FOREGROUND_SERVICE_ENABLED")

  lazy val uiPreferences = uiPreferencesFrom(context)

  lazy val wsForegroundEnabledPref = uiPreferenceBooleanSignal(wsForegroundKey)

  def callingV3  = uiPreferences.getString(callingV3Key,         if (ZmsVersion.DEBUG) "2" else "0") //0 (calling v2) by default for production, v3 (2) for debug
  def gcmEnabled = uiPreferences.getBoolean(gcmEnabledKey,       true) //true by default for production
  def v31AssetsEnabled = false

  override lazy val preferences = preferencesFrom(context)

  override def withPreferences[A](body: SharedPreferences => A, prefs: => SharedPreferences = preferences): CancellableFuture[A] = dispatcher(body(prefs))

  override def editPreferences(body: SharedPreferences.Editor => Unit, prefs: => SharedPreferences = preferences): CancellableFuture[Boolean] = dispatcher {
    val editor = prefs.edit()
    body(editor)
    editor.commit()
  }

}

object PreferenceService {
  def uiPreferencesFrom(context: Context) = context.getSharedPreferences("com.waz.zclient.user.preferences", Context.MODE_PRIVATE)
  def preferencesFrom(context: Context) = context.getSharedPreferences("zmessaging", Context.MODE_PRIVATE)

  lazy val preferenceDispatcher = new SerialDispatchQueue()

  class Pref[A](prefs: => SharedPreferences, key: String, load: => A, save: (SharedPreferences.Editor, A) => Unit) extends Preference[A] {

    override protected implicit def dispatcher = preferenceDispatcher

    override def default: A = load

    override def apply() = Future { load }

    override def update(value: A) = Future {
      val editor = prefs.edit()
      save(editor, value)
      editor.commit()
    }

    override lazy val signal: SourceSignal[A] = new SourceSignal[A](Some(load)) {

      private val listener = new OnSharedPreferenceChangeListener {
        override def onSharedPreferenceChanged(sharedPreferences: SharedPreferences, k: String): Unit =
          if (key == k) {
            if (Thread.currentThread() == Looper.getMainLooper.getThread) publish(load, Threading.Ui)
            else publish(load)
          }
      }

      override def onWire():Unit = {
        value = Some(load)
        Threading.Ui { prefs.registerOnSharedPreferenceChangeListener(listener) } .map { _ =>
          publish(load, Threading.Background) // load value again after registering the listener (it could have been changed in meantime)
        } (Threading.Background)
      }

      override def onUnwire(): Unit =
        Threading.Ui { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
  }
}
