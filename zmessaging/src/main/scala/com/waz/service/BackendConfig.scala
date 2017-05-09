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

import com.waz.service.push.GcmGlobalService.PushSenderId

case class BackendConfig(baseUrl: String, pushUrl: String, gcmSenderId: PushSenderId, environment: String) {
  import BackendConfig._
  if (gcmSenderId != stagingSenderId && gcmSenderId != prodSenderId) throw new IllegalArgumentException(s"Unknown sender id: $gcmSenderId")
}

object BackendConfig {
  val Seq(stagingSenderId, prodSenderId) = Seq("723990470614", "782078216207") map PushSenderId

  val StagingBackend = BackendConfig("https://staging-nginz-https.zinfra.io", "https://staging-nginz-ssl.zinfra.io/await", stagingSenderId, "staging")
  val ProdBackend = BackendConfig("https://prod-nginz-https.wire.com", "https://prod-nginz-ssl.wire.com/await", prodSenderId, "prod")

  lazy val byName = Seq(StagingBackend, ProdBackend).map(b => b.environment -> b).toMap

  def apply(baseUrl: String): BackendConfig = BackendConfig(baseUrl, "", stagingSenderId, "") // XXX only use for testing!
}
