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
package com.waz.sync.client

import com.waz.ZLog._
import com.waz.model._
import com.waz.service.BackendConfig
import com.waz.threading.Threading
import com.waz.utils.wrappers.URI
import com.waz.znet.ContentEncoder.EmptyContentEncoder
import com.waz.znet.Response.{ResponseBodyDecoder, SuccessHttpStatus}
import com.waz.znet.ResponseConsumer.JsonConsumer
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet._

import scala.util.Try

class VersionBlacklistClient(netClient: ZNetClient, backendConfig: BackendConfig) {
  import Threading.Implicits.Background
  import VersionBlacklistClient._
  private implicit val tag: LogTag = logTagFor[VersionBlacklistClient]

  def loadVersionBlacklist(): ErrorOrResponse[VersionBlacklist] = {
    netClient.withErrorHandling("loadVersionBlacklist", Request(baseUri = Some(blacklistsUrl(backendConfig.environment)), requiresAuthentication = false, decoder = Some(decoder))(EmptyContentEncoder)) {
      case Response(SuccessHttpStatus(), VersionBlacklistExtractor(blacklist), _) => blacklist
    }
  }

  def blacklistsUrl(env: String) = URI.parse(s"https://clientblacklist.wire.com/${Option(env) filterNot (_.isEmpty) getOrElse "prod"}/android")
}

object VersionBlacklistClient {
  val decoder = new ResponseBodyDecoder {
    override def apply(contentType: String, contentLength: Long): ResponseConsumer[_ <: ResponseContent] = new JsonConsumer(contentLength)
  }

  object VersionBlacklistExtractor {
    def unapply(resp: ResponseContent): Option[VersionBlacklist] = resp match {
      case JsonObjectResponse(js) => Try(VersionBlacklist.Decoder(js)).toOption
      case _ => None
    }
  }
}
