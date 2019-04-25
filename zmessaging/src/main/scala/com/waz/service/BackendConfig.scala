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

import com.waz.sync.client.CustomBackendClient.BackendConfigResponse
import com.waz.utils.wrappers.URI
import com.waz.znet.ServerTrust


class BackendConfig(private var _environment: String,
                    private var _baseUrl: URI,
                    private var _websocketUrl: URI,
                    private var _blacklistHost: URI,
                    val firebaseOptions: FirebaseOptions,
                    val pin: CertificatePin = ServerTrust.wirePin) {

  val pushSenderId: String = firebaseOptions.pushSenderId
  
  def environment: String = _environment
  def baseUrl: URI = _baseUrl
  def websocketUrl: URI = _websocketUrl
  def blacklistHost: URI = _blacklistHost

  def update(configResponse: BackendConfigResponse): Unit = {
    _environment = configResponse.title
    _baseUrl = URI.parse(configResponse.endpoints.backendURL.toString)
    _websocketUrl = URI.parse(configResponse.endpoints.backendWSURL.toString)
    _blacklistHost = URI.parse(configResponse.endpoints.blackListURL.toString)
  }
}

object BackendConfig {
  def apply(environment: String,
            baseUrl: String,
            websocketUrl: String,
            blacklistHost: String,
            firebaseOptions: FirebaseOptions,
            pin: CertificatePin = ServerTrust.wirePin): BackendConfig = new BackendConfig(
      environment,
      URI.parse(baseUrl),
      URI.parse(websocketUrl),
      URI.parse(blacklistHost),
      firebaseOptions,
      pin)
}

//cert is expected to be base64-encoded
case class CertificatePin(domain: String, cert: Array[Byte])

case class FirebaseOptions(pushSenderId: String, appId: String, apiKey: String)
