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

import com.waz.utils.PasswordValidator._

/// A minimal password validator that enforces length only.
class PasswordValidator(val minLength: Int, val maxLength: Int) {

  private val lengthRule: Rule = p => {
    val length = p.codePointCount(0, p.length)
    length >= minLength && length <= maxLength
  }

  private var rules: Seq[Rule] = Seq(lengthRule)

  def add(rule: Rule): Unit = rules = rules :+ rule
  def add(rules: Seq[Rule]): Unit = this.rules = this.rules ++ rules
  def isValidPassword(password: String): Boolean = rules.forall(_(password))
}

/// A strong password validator that enforces length and the existence of a lowercase,
/// uppercase, digit and special character.
class StrongPasswordValidator(minLength: Int)
  extends PasswordValidator(minLength, maxLength = 101) {

  add(rules = Seq(
    p => p.exists(_.isLower),
    p => p.exists(_.isUpper),
    p => p.exists(_.isDigit) // TODO: add special rule
  ))
}

object PasswordValidator {
  type Rule = String => Boolean
}
