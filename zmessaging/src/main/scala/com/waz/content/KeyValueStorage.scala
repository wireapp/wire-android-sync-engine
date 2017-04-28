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
package com.waz.content

import android.content.Context
import com.waz.content.Preference.PrefCodec
import com.waz.model.KeyValueData
import com.waz.model.KeyValueData.KeyValueDataDao
import com.waz.threading.Threading
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.{CachedStorageImpl, TrimmingLruCache}

import scala.concurrent.{ExecutionContext, Future}

/**
  * General preference storage in user db.
  * This can be used similarly to PreferenceService, but it keeps separate data for each logged in user as opposed to PreferenceService which uses single global preferences file.
  */
class KeyValueStorage(context: Context, storage: ZmsDatabase) extends CachedStorageImpl[String, KeyValueData](new TrimmingLruCache(context, Fixed(128)), storage)(KeyValueDataDao, "KeyValueStorage_Cached") {
  import KeyValueStorage._
  import Threading.Implicits.Background

  def getPref(key: String): Future[Option[String]] = get(key).map(_.map(_.value))
  def setPref(key: String, value: String): Future[KeyValueData] = put(key, KeyValueData(key, value))
  def delPref(key: String): Future[Unit] = remove(key)
  def decodePref[A](key: String, dec: String => A): Future[Option[A]] = getPref(key).map(_.map(dec))

  def lastSlowSyncTimestamp = decodePref(LastSlowSyncTimeKey, java.lang.Long.parseLong)
  def lastSlowSyncTimestamp_=(time: Long): Unit = setPref(LastSlowSyncTimeKey, String.valueOf(time))

  def shouldSyncConversations = decodePref(ShouldSyncConversations, java.lang.Boolean.parseBoolean)
  def shouldSyncConversations_=(should: Boolean): Unit = setPref(ShouldSyncConversations, String.valueOf(should))

  def keyValuePref[A: PrefCodec](key: String, default: A) = new KeyValuePref[A](this, key, default)
}

object KeyValueStorage {
  val LastSlowSyncTimeKey = "last_slow_sync_time"
  val Verified = "verified"
  val SelectedConvId = "selected_conv_id"
  val SpotifyRefreshToken = "spotify_refresh_token"
  val ShouldSyncConversations = "should_sync_conversations"

  class KeyValuePref[A](storage: KeyValueStorage, key: String, val default: A)(implicit val trans: PrefCodec[A], override implicit val dispatcher: ExecutionContext) extends Preference[A] {
    def apply(): Future[A] = storage.decodePref(key, trans.decode).map(_.getOrElse(default))

    def update(value: A): Future[Unit] = {
      storage.setPref(key, trans.encode(value)) .map { _ => signal ! value }
    }
  }
}
