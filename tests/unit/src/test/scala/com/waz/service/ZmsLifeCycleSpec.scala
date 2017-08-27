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

import com.waz.model.AccountId
import com.waz.service.ZmsLifeCycle.{LifeCycleState, _}
import com.waz.specs.AndroidFreeSpec

class ZmsLifeCycleSpec extends AndroidFreeSpec {

  feature ("Calculating lifecycle state") {
    scenario("No logged in accounts and no active account should result in stopped") {
      LifeCycleState(None, Set.empty, 0, 0) shouldEqual Stopped
    }

    scenario("Active account with registered ui should result in UiActive with that account id") {
      val acc = AccountId()
      LifeCycleState(Some(acc), Set(acc), 1, 0) shouldEqual UiActive(acc, Set(acc))
    }

    scenario("Active account without registered ui or push should result in idle lifecycle state") {
      val acc = AccountId()
      LifeCycleState(Some(acc), Set(acc), 0, 0) shouldEqual Idle(Set(acc))
    }

    scenario("Active account with registered push should result in Active lifecycle state") {
      val acc = AccountId()
      LifeCycleState(Some(acc), Set(acc), 0, 1) shouldEqual Active(Set(acc))
    }

    scenario("Active account without logged in accounts but registered ui should result in UI active") {
      val acc = AccountId()
      LifeCycleState(Some(acc), Set.empty, 1, 0) shouldEqual UiActive(acc, Set.empty)
    }
  }


}
