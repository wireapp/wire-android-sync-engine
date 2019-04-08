/*
 * Wire
 * Copyright (C) 2019 Wire Swiss GmbH
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
package com.waz.utils

import org.scalatest.{FeatureSpec, Matchers}

class PasswordValidatorSpec extends FeatureSpec with Matchers {

  private lazy val sut = new StrongPasswordValidator(minLength = 8)

  // TODO: Add cases
  private val validPasswords = Seq(
    "Passw0rd"
  )

  // TODO: Add cases
  private val invalidPasswords = Seq(
    "short"
  )

  feature("Strong password") {

    scenario("validates valid passwords") {
      validPasswords.foreach { p =>
        assert(sut.isValidPassword(p), s"-> p: $p should be valid.")
      }
    }

    scenario("validates invalid passwords") {
      invalidPasswords.foreach { p =>
        assert(!sut.isValidPassword(p), s"-> p: $p should not be valid.")
      }
    }
  }
}
