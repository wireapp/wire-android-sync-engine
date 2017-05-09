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
package com.waz.utils.wrappers

import com.localytics.android.{Localytics => ALocalytics}

object Localytics {
  private var util: Option[LocalyticsUtil] = Some(new LocalyticsUtil {
    override def setPushDisabled(disabled: Boolean) = ALocalytics.setPushDisabled(disabled)
    override def setPushRegistrationId(regId: String) = ALocalytics.setPushRegistrationId(regId)
  })

  def setUtil(util: Option[LocalyticsUtil]) = this.util = util

  def setPushDisabled(disabled: Boolean): Unit = util.foreach(_.setPushDisabled(disabled))
  def setPushRegistrationId(regId: String): Unit = util.foreach(_.setPushRegistrationId(regId))
}

trait LocalyticsUtil {
  def setPushDisabled(disabled: Boolean): Unit
  def setPushRegistrationId(regId: String): Unit
}
