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
package com.waz

import com.waz.ZLog.LogTag

//TODO might be nicer to group this together with tracking and have Analytics and Tracking listen reactively to events from the app
trait Analytics {
  def saveException(t: Throwable, description: String)(implicit tag: LogTag): Unit
  def shouldReport(t: Throwable): Boolean
}

object Analytics extends Analytics {

  trait NoReporting { self: Throwable => }

  var instance: Analytics = NoAnalytics //to be set by application

  override def saveException(t: Throwable, description: String)(implicit tag: LogTag): Unit = instance.saveException(t, description)

  override def shouldReport(t: Throwable): Boolean = instance.shouldReport(t)
}

object NoAnalytics extends Analytics {

  override def saveException(t: Throwable, description: String)(implicit tag: LogTag): Unit = ()

  override def shouldReport(t: Throwable): Boolean = false
}
