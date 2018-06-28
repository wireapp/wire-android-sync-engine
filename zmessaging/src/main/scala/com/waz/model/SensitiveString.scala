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
package com.waz.model

import com.waz.utils.sha2
import scala.language.implicitConversions

//Always obfuscates content, even in debug builds
case class SensitiveString(str: String) extends AnyVal {

  def isEmpty  = str.isEmpty
  def length   = str.length
  def nonEmpty = str.nonEmpty

  def trim = SensitiveString(str.trim)
  def take(x: Int) = SensitiveString(str.take(x))

  def apply(i: Int): Char = str(i)

  def contains(substr: String): Boolean = str.contains(substr)
  def compareTo(other: SensitiveString): Int = str.compareTo(other.str)

  def split(regex: String): Array[String] = str.split(regex)

  def substring(begin: Int, end: Int): SensitiveString = SensitiveString(str.substring(begin, end))
  def substring(beginIndex: Int): SensitiveString = SensitiveString(str.substring(beginIndex))

  def indexWhere(p: Char => Boolean, from: Int): Int = str.indexWhere(p, from)

  override def toString = s"SensitiveString(${sha2(str)})"
}

object SensitiveString extends (String => SensitiveString) {

  implicit def fromSensitiveString(cs: SensitiveString): String = cs.str

  implicit def toSensitiveString(str: String): SensitiveString = SensitiveString(str)

  val Empty = SensitiveString("")
}