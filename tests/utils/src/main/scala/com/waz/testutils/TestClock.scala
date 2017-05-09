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

import com.waz.ZLog
import org.threeten.bp.{Clock, Instant, ZoneId}
import com.waz.utils._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

/**
  * A custom implementation of FixedClock that allows us to advance the current instant the clock holds to simulate
  * the flow of time in a controlled manner.
  */
class TestClock extends Clock {
  private var _instant = Instant.EPOCH

  //not used
  def getZone = null

  def withZone(zone: ZoneId) = null

  override def millis = _instant.toEpochMilli

  def instant = _instant

  override def equals(obj: Any) =
    obj match {
      case other: TestClock => _instant == other._instant
      case _ => false
    }

  override def hashCode = _instant.hashCode

  override def toString = s"FixedClock(${_instant})"

  def advance(millis: Long): Unit = advance(millis.millis)

  def advance(duration: FiniteDuration): Unit = {

    if (ZLog.testLogging) {
      val s = duration.toSeconds
      val m = duration.toMillis % s * 1000
      println(s"Advancing clock: $s.$m seconds")
    }
    _instant += duration
  }

  def reset(): Unit = _instant = Instant.EPOCH
}
