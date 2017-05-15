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
package com.waz.znet

import java.security.cert.X509Certificate
import javax.net.ssl._

import com.koushikdutta.async.http.AsyncHttpClientMiddleware.GetSocketData
import com.koushikdutta.async.http.spdy.SpdyMiddleware
import com.koushikdutta.async.http.{SSLEngineSNIConfigurator, _}
import com.koushikdutta.async._
import com.waz.threading.Threading
import com.waz.utils._

import scala.concurrent.Future

/**
 * Replaces ssl context, trust managers and verifiers for Robolectric testing.
 * Default ssl implementation in AndroidAsync doesn't work correctly when run in RoboTests, so we need to hack it a bit.
  */
object TestClientWrapper {
  import Threading.Implicits.Background

  def apply(client: AsyncHttpClient): Future[ClientWrapper] = Future {

    def prepareSSlMiddleware() = new SpdyMiddleware(client) {

      addEngineConfigurator(new SSLEngineSNIConfigurator)

      setSSLContext(returning(SSLContext.getInstance("TLSv1.2")) { _.init(null, null, null) })

      setTrustManagers(Array(new X509TrustManager {
        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = throw new SSLException("unexpected call to checkClientTrusted")

        override def getAcceptedIssuers: Array[X509Certificate] = throw new SSLException("unexpected call to getAcceptedIssuers")
      }))

      override def createConfiguredSSLEngine(data: GetSocketData, host: String, port: Int): SSLEngine = {
        val sslContext = getSSLContext
        val sslEngine = sslContext.createSSLEngine(host, port)
        import scala.collection.JavaConversions._
        for (configurator <- engineConfigurators) {
          configurator.configureEngine(sslEngine, data, host, port)
        }

        sslEngine
      }
    }

    client.getMiddleware.clear()
    client.insertMiddleware(client.getSocketMiddleware)
    client.insertMiddleware(prepareSSlMiddleware())
    client.insertMiddleware(new HttpTransportMiddleware)

    new ClientWrapperImpl(client)
  }

  def apply(): Future[ClientWrapper] = apply(new AsyncHttpClient(new AsyncServer))
}

