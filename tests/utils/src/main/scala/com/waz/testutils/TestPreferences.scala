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
package com.waz.testutils

import java.util

import android.content.SharedPreferences
import android.content.SharedPreferences.{Editor, OnSharedPreferenceChangeListener}
import com.waz.ZLog
import com.waz.service.PreferenceService
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.returning

import scala.language.existentials

class TestPreferences extends PreferenceService {

  override val dispatcher = new SerialDispatchQueue(name = "TestPreferenceQueue")

  private var _preferences   = new TestSharedPreferences
  private var _uiPreferences = new TestSharedPreferences

  override def preferences   = _preferences
  override def uiPreferences = _uiPreferences

  def reset() = {
    _preferences   = new TestSharedPreferences
    _uiPreferences = new TestSharedPreferences
  }
}

class TestSharedPreferences extends SharedPreferences {

  /**
    * Values in this class need to be set on a different thread. If we allow the main thread to be used, then
    * preferences won't update while we are waiting on them
    */
  private implicit val dispatcher = new SerialDispatchQueue(name = "TestSharedPreferencesQueue")

  private var listeners = Set.empty[OnSharedPreferenceChangeListener]

  private var values = Map.empty[String, String]

  override def unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) =
    listeners -= listener

  override def registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) =
    listeners += listener

  override def edit() = new Editor {

    private var changes = Set.empty[String]

    override def clear() = {
      dispatcher {
        values = Map.empty[String, String]
      }
      this
    }

    override def remove(key: String) = {
      dispatcher {
        changes += key
        values -= key
      }
      this
    }

    override def putString(key: String, value: String) = {
      dispatcher {
        changes += key
        values += (key -> value)
      }
      this
    }

    /**
      * We use `PrefCodec`s in our PreferenceService and save everything as a String. This allows us to simplify the type
      * mess down here.
      */
    override def putFloat(key: String, value: Float)                 = ???
    override def putBoolean(key: String, value: Boolean)             = ???
    override def putInt(key: String, value: Int)                     = ???
    override def putLong(key: String, value: Long)                   = ???
    override def putStringSet(key: String, values: util.Set[String]) = ???

    override def apply() = commit()

    override def commit() = {
      dispatcher {
        for {
          listener <- listeners
          change  <- changes
        } {
          try {
            listener.onSharedPreferenceChanged(TestSharedPreferences.this, change)
          } catch {
            case e: Throwable =>
              //TODO fix this
              // not too sure exactly what's causing this, but the mocks seem to be out of scope even after some preferences
              // are still processing values - this gives us NPEs in the tests. They don't fail, but they just don't look nice.
              println(s"Tried to update shared preference listener after test completed")
          }
        }
      }
      true
    }
  }

  override def getString(key: String, defValue: String) =
    values.get(key) match {
      case Some(value) => value
      case _           => defValue
    }

  override def getFloat(key: String, defValue: Float)                 = ???
  override def getLong(key: String, defValue: Long)                   = ???
  override def getStringSet(key: String, defValues: util.Set[String]) = ???
  override def getBoolean(key: String, defValue: Boolean)             = ???
  override def getInt(key: String, defValue: Int)                     = ???
  override def getAll                                                 = ???

  override def contains(key: String) = values.contains(key)
}
