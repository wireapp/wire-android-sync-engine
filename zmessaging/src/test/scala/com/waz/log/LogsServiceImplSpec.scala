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
package com.waz.log

import com.waz.content.GlobalPreferences
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.specs.AndroidFreeSpec
import com.waz.testutils.TestGlobalPreferences
import com.waz.threading.{DispatchQueue, Threading}

class LogsServiceImplSpec extends AndroidFreeSpec with DerivedLogTag {

  private implicit val ec: DispatchQueue = Threading.Background

  private val prefs = new TestGlobalPreferences
  private val LogsEnabled = GlobalPreferences.LogsEnabled

  feature("Safe logging") {

    scenario("logs are disabled by default") {
      // Given the preference is false by default
      result(prefs(LogsEnabled).apply()) shouldBe false

      // When
      new LogsServiceImpl(prefs, logsEnabledByDefault = false)

      // Then preference is still false
      result(prefs(LogsEnabled).apply()) shouldBe false
    }

    scenario("logs are enabled by default") {
      // Given the preference is false by default
      result(prefs(LogsEnabled).apply()) shouldBe false

      // When
      new LogsServiceImpl(prefs, logsEnabledByDefault = true)

      // Then preference is updated
      result(prefs(LogsEnabled).apply()) shouldBe true
    }
  }
}
