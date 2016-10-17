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

import android.content.Context
import android.renderscript.RSRuntimeException
import com.waz.Analytics.NoReporting
import com.waz.ZLog.{LogTag, error}
import com.waz.service.ZMessaging
import net.hockeyapp.android.{CrashManagerListener, ExceptionHandler}

import scala.util.Try

object HockeyApp extends Analytics {

  override def saveException(t: Throwable, description: String)(implicit tag: LogTag): Unit = {
    error(description, t)
    if (shouldReport(t)) {
      ExceptionHandler.saveException(t, new CrashManagerListener {
        override def shouldAutoUploadCrashes: Boolean = true
        override def getUserID: String = Try(ZMessaging.context.getSharedPreferences("zprefs", Context.MODE_PRIVATE).getString("com.waz.device.id", "???")).getOrElse("????")
        override def getDescription: String = s"zmessaging - $tag - $description"
      })
    }
  }

  override def shouldReport(t: Throwable) = t match {
    case _: NoReporting => false
    case _: RSRuntimeException => false
    case _ => true
  }
}
