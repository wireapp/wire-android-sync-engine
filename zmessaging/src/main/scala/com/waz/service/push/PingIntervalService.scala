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
package com.waz.service.push

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.{NetworkMode, ZmsVersion}
import com.waz.content.Preferences.PrefKey
import com.waz.content.Preferences.Preference.PrefCodec
import com.waz.content.UserPreferences
import com.waz.service.push.PingIntervalService.NetworkStats.NetworkStatsCodec
import com.waz.service.{NetworkModeService, ZmsLifeCycle}
import com.waz.utils.events.Subscription
import com.waz.utils.{JsonDecoder, JsonEncoder}
import org.json.{JSONArray, JSONObject}
import org.threeten.bp.Instant

import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.concurrent.duration._


/**
  * Service responsible for scheduling recurring ping on web socket client.
  * Keeps statistics of socket disconnection (separate for different network types) and tries to adjust ping interval
  * based on historical performance.
  *
  * @see com.waz.service.WebSocketClientService.ConnectionStats ConnectionStats
  */
class PingIntervalService(lifecycle: ZmsLifeCycle,
                          network:   NetworkModeService,
                          wsService: WebSocketClientService,
                          userPrefs: UserPreferences) {
  import PingIntervalService._
  import com.waz.utils._
  import com.waz.utils.events.EventContext.Implicits.global

  import scala.concurrent.duration._

  val stats = userPrefs.preference(PrefKey[Map[NetworkMode, NetworkStats]]("ping_interval_network_stats")(NetworkStatsCodec))
  val statsSignal = stats.signal

  val currentStats = for {
    mode <- network.networkMode
    ns <- statsSignal
  } yield {
    ns.getOrElse(mode, NetworkStats(mode))
  }

  val interval = currentStats.map(_.pingInterval)

  private var subs = Seq.empty[Subscription]

  for {
    ws <- wsService.client
    active <- lifecycle.active
    interval <- interval
  } {
    ws.foreach { _.scheduleRecurringPing(if (active) PING_INTERVAL_FOREGROUND else interval) }
  }

  wsService.connectionStats { cs =>
    subs foreach { _.destroy() }
    subs = Seq(
      cs.maxInactiveDuration { updateMaxInactive(_) },
      cs.lostOnPingDuration { updateLostOnPing(_) },
      cs.aliveDuration { updateAlive(_) }
    )
  }

  // sets ping interval, can be used from settings,
  // resets other stats so that adjustment doesn't kick in right away
  def setPingInterval(d: FiniteDuration) = {
    val interval = (d max MIN_PING_INTERVAL) min MAX_PING_INTERVAL
    updateStats { st =>
      if (st.pingInterval == interval) st
      else st.copy(pingInterval = d, lostOnPing = Nil, alive = Nil, maxInactive = Nil)
    }
  }

  private def updateLostOnPing(d: FiniteDuration) =
    if (d >= MIN_PING_INTERVAL)
      updateStats { st => st.copy(lostOnPing = d +: st.lostOnPing.take(9)) }

  private def updateAlive(d: FiniteDuration) =
    if (d >= MIN_PING_INTERVAL)
      updateStats { st => st.copy(alive = d +: st.alive.take(9)) }

  private def updateMaxInactive(d: FiniteDuration) =
    if (d >= MIN_PING_INTERVAL)
      updateStats { st =>
        val ds = st.maxInactive
        if (ds.isEmpty || !st.lastUpdate.isToday) {
          st.copy(maxInactive = d +: ds.take(9))
        } else {
          st.copy(maxInactive = ds.updated(0, ds.head max d))
        }
      }

  private def updateStats(f: NetworkStats => NetworkStats) = {
    val mode = network.networkMode.currentValue.getOrElse(NetworkMode.OFFLINE)
    stats.mutate { ns =>
      val updated = f(ns.getOrElse(mode, NetworkStats(mode)))
      ns.updated(mode, updateInterval(updated).copy(lastUpdate = Instant.now))
    }
  }

  // compute adjusted ping interval on stats change
  private def updateInterval(st: NetworkStats) = {
    verbose(s"computing ping interval for $st")
    implicit def ordering: Ordering[FiniteDuration] = Ordering.Long.on[FiniteDuration](_.toMillis)
    // TODO: check tracking data, improve interval adjustment logic based on prev performance and enable it in prod
    if (ZmsVersion.DEBUG) {
      if (st.lostOnPing.size > 3 || st.alive.size > 8) {
        // lower ping interval to prevent disconnects, TODO: improve logic here, need more data from tracking
        val alive = if (st.alive.size < 3) DEFAULT_PING_INTERVAL else st.alive.max
        val lostPing = if (st.lostOnPing.isEmpty) DEFAULT_PING_INTERVAL else st.lostOnPing.min
        val maxInactive = if (st.maxInactive.isEmpty) DEFAULT_PING_INTERVAL else st.maxInactive.max min DEFAULT_PING_INTERVAL
        val interval = (st.pingInterval - 15.seconds) min (((lostPing min alive) * 3 + maxInactive) / 4)
        st.copy(pingInterval = (interval max MIN_PING_INTERVAL) min MAX_PING_INTERVAL, lostOnPing = Nil, alive = Nil, maxInactive = st.maxInactive.take(2))
      } else if (st.maxInactive.size > 5 && st.maxInactive.take(5).min >= st.pingInterval.minus(15.seconds) && st.pingInterval < DEFAULT_PING_INTERVAL) {
        // several days without any issues, inactive times are close to ping interval, we might risk increasing it a bit
        verbose("increasing ping interval")
        st.copy(pingInterval = st.pingInterval + 15.seconds)
      } else {
        verbose(s"keeping interval unchanged")
        st
      }
    } else {
      st // return unchanged, auto adjustment will be done in dev only (for now)
    }
  }
}

object PingIntervalService {
  val PING_INTERVAL_FOREGROUND  = 30.seconds

  // following only apply for background ping
  val DEFAULT_PING_INTERVAL     = 9.minutes
  val MIN_PING_INTERVAL         = 2.minutes // no point it doing it more often,
  val MAX_PING_INTERVAL         = 14.minutes // backend disconnects after 15 minutes of inactivity, we need to ping more often than that


  case class NetworkStats(net: NetworkMode,
                          pingInterval: FiniteDuration = DEFAULT_PING_INTERVAL,
                          maxInactive: Seq[FiniteDuration] = Nil,
                          lostOnPing: Seq[FiniteDuration] = Nil,
                          alive: Seq[FiniteDuration] = Nil,
                          lastUpdate: Instant = Instant.now)

  object NetworkStats {

    implicit object Encoder extends JsonEncoder[NetworkStats] {
      override def apply(v: NetworkStats): JSONObject = JsonEncoder { o =>
        o.put("net", v.net.name)
        o.put("interval", v.pingInterval.toMillis)
        o.put("time", v.lastUpdate.toEpochMilli)
        o.put("maxInactive", JsonEncoder.array(v.maxInactive) { (arr, d) => arr.put(d.toMillis) })
        o.put("ping", JsonEncoder.array(v.lostOnPing) { (arr, d) => arr.put(d.toMillis) })
        o.put("alive", JsonEncoder.array(v.alive) { (arr, d) => arr.put(d.toMillis) })
      }
    }

    implicit object Decoder extends JsonDecoder[NetworkStats] {
      import JsonDecoder._
      override def apply(implicit js: JSONObject): NetworkStats =
        NetworkStats(NetworkMode.valueOf('net), 'interval, 'maxInactive, 'ping, 'alive, 'time)
    }

    implicit lazy val NetworkStatsCodec = PrefCodec.apply[Map[NetworkMode, NetworkStats]](
      v => JsonEncoder.arr(v.values).toString,
      str => Try(new JSONArray(str)).toOption.fold(Map.empty[NetworkMode, NetworkStats]) { arr =>
        JsonDecoder.array[NetworkStats](arr).map(s => s.net -> s)(scala.collection.breakOut)
      },
      Map.empty)
  }
}
