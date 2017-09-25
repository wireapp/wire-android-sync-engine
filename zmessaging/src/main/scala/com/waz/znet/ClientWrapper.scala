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

import android.annotation.TargetApi
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import com.google.android.gms.security.ProviderInstaller
import com.koushikdutta.async._
import com.koushikdutta.async.future.{FutureCallback, Future => AFuture}
import com.koushikdutta.async.http._
import com.koushikdutta.async.http.callback._
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.service.ZMessaging
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.wrappers.Context
import org.apache.http.conn.ssl.{AbstractVerifier, StrictHostnameVerifier}

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.Try

trait ClientWrapper {
  def execute(request: HttpRequest, callback: HttpConnectCallback): CancellableFuture[HttpResponse]
  def websocket(request: HttpRequest, protocol: String, callback: AsyncHttpClient.WebSocketConnectCallback): CancellableFuture[WebSocket]
  def stop(): Unit
}

class ClientWrapperImpl(val client: AsyncHttpClient) extends ClientWrapper {
  import ClientWrapper._
  import Threading.Implicits.Background

  override def execute(request: HttpRequest, callback: HttpConnectCallback): CancellableFuture[HttpResponse] = client.execute(request, callback).map(res => HttpResponse(res))
  override def websocket(request: HttpRequest, protocol: String, callback: AsyncHttpClient.WebSocketConnectCallback): CancellableFuture[WebSocket] = client.websocket(request, protocol, callback)
  override def stop(): Unit = client.getServer().stop()
}


/**
 * Wrapper for instrumenting of AsyncHttpClient, by default is empty, but will be replaced in tests.
  */
object ClientWrapper {
  import Threading.Implicits.Background

  import scala.language.implicitConversions

  implicit def aFutureToCancellable[T](f: AFuture[T]): CancellableFuture[T] = {
    val p = Promise[T]()

    f.setCallback(new FutureCallback[T] {
      override def onCompleted(ex: Exception, result: T): Unit = Option(ex) match {
        case None => p.success(result)
        case Some(ex) => p.failure(ex)
      }
    })

    new CancellableFuture(p){
      override def cancel()(implicit tag: LogTag): Boolean = f.cancel(true)
    }
  }

  def unwrap(wrapper: ClientWrapper): AsyncHttpClient = wrapper match {
    case cl: ClientWrapperImpl => cl.client
    case other => throw new IllegalArgumentException(s"Trying to unwrap an AsyncHttpClient, but got: $other of type ${other.getClass.getName}")
  }

  def apply(client: AsyncHttpClient, context: Context = ZMessaging.context): Future[ClientWrapper] = Future {
    init(client, context)
    new ClientWrapperImpl(client)
  }

  def apply(): Future[ClientWrapper] = apply(new AsyncHttpClient(new AsyncServer))

  val domains @ Seq(zinfra, wire) = Seq("zinfra.io", "wire.com")
  val protocol = "TLSv1.2"
  val cipherSuite = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"

  private def init(client: AsyncHttpClient, context: Context): Unit = {
    val installGmsCoreOpenSslProvider = Future (try {
      ProviderInstaller.installIfNeeded(context)
    } catch {
      case t: Throwable => debug("Looking up GMS Core OpenSSL provider failed fatally.") // this should only happen in the tests
    })

    installGmsCoreOpenSslProvider map { _ =>
      // using specific hostname verifier to ensure compatibility with `isCertForDomain` (below)
      client.getSSLSocketMiddleware.setHostnameVerifier(new StrictHostnameVerifier)
      client.getSSLSocketMiddleware.setTrustManagers(Array(new X509TrustManager {
        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {
          debug(s"checking certificate for authType $authType, name: ${chain(0).getSubjectDN.getName}")
          chain.headOption.fold(throw new SSLException("expected at least one certificate!")) { cert =>
            val tm = if (isCertForDomain(zinfra, cert) || isCertForDomain(wire, cert)) {
              verbose("using backend trust manager")
              ServerTrust.backendTrustManager
            } else {
              verbose("using system trust manager")
              ServerTrust.systemTrustManager
            }
            try {
              tm.checkServerTrusted(chain, authType)
            } catch {
              case e: Throwable =>
                error("certificate check failed", e)
                throw e
            }
          }
        }

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = throw new SSLException("unexpected call to checkClientTrusted")

        override def getAcceptedIssuers: Array[X509Certificate] = throw new SSLException("unexpected call to getAcceptedIssuers")

        /**
          * Checks if certificate matches given domain.
          * This is used to check if currently verified server is known to wire, and we should do certificate pinning for it.
          *
          * Warning: it's very important that this implementation matches used HostnameVerifier.
          * If HostnameVerifier accepts this cert with some wire sub-domain then this function must return true,
          * otherwise pinning will be skipped and we risk MITM attack.
          */
        private def isCertForDomain(domain: String, cert: X509Certificate): Boolean = {
          def iter(arr: Array[String]) = Option(arr).fold2(Iterator.empty, _.iterator)
          (iter(AbstractVerifier.getCNs(cert)) ++ iter(AbstractVerifier.getDNSSubjectAlts(cert))).exists(_.endsWith(s".$domain"))
        }
      }))

      client.getSSLSocketMiddleware.setSSLContext(returning(SSLContext.getInstance("TLSv1.2")) { _.init(null, null, null) })

      client.getSSLSocketMiddleware.addEngineConfigurator(new AsyncSSLEngineConfigurator {
        override def configureEngine(engine: SSLEngine, data: AsyncHttpClientMiddleware.GetSocketData, host: String, port: Int): Unit = {
          debug(s"configureEngine($host, $port)")

          if (domains.exists(host.endsWith)) {
            verbose("restricting to TLSv1.2")
            engine.setSSLParameters(returning(engine.getSSLParameters) { params =>
              if (engine.getSupportedProtocols.contains(protocol)) params.setProtocols(Array(protocol))
              else warn(s"$protocol not supported by this device, falling back to defaults.")

              if (engine.getSupportedCipherSuites.contains(cipherSuite)) params.setCipherSuites(Array(cipherSuite))
              else warn(s"cipher suite $cipherSuite not supported by this device, falling back to defaults.")
            })

            verbose("enabling SNI")
            SNIConfigurator(engine, host, port)
          }
        }
      })

    }
  }
}


object SNIConfigurator {

  lazy val instance =
    if (SDK_INT >= Build.VERSION_CODES.N) new NougatConfigurator
    else new ReflectionConfigurator

  def apply(engine: SSLEngine, host: String, port: Int) =
    instance.configureEngine(engine, null, host, port)


  @TargetApi(24)
  class NougatConfigurator extends AsyncSSLEngineConfigurator {
    override def configureEngine(engine: SSLEngine, data: AsyncHttpClientMiddleware.GetSocketData, host: String, port: Int): Unit = {
      import javax.net.ssl._
      import scala.collection.JavaConverters._

      engine.setSSLParameters(returning(engine.getSSLParameters) { params =>
        params.setServerNames(Seq[SNIServerName](new SNIHostName(host)).asJava)
      })
    }
  }

  /**
    * Enables SNI extension using reflection.
    * This extension was supported in SSLEngine since android 2.3 but was not exposed in public api,
    * it was only accessible from HTTPUrlConnection (which we don't use).
    * AndroidAsync used reflection for that in SSLEngineSNIConfigurator, but latest versions started using
    * obfuscation and that solution no longer works.
    * This class finds obfuscated `sslParameters` by iterating through all fields.
    */
  class ReflectionConfigurator extends AsyncSSLEngineConfigurator {

    class EngineHolder(engineClass: Class[_]) {
      val peerHost = returning(engineClass.getSuperclass.getDeclaredField("peerHost")) { _.setAccessible(true) }
      val peerPort = returning(engineClass.getSuperclass.getDeclaredField("peerPort")) { _.setAccessible(true) }
      val sslParameters = returning(engineClass.getDeclaredFields.find { field => Try(field.getType.getDeclaredField("useSni")).isSuccess }) { _ foreach(_.setAccessible(true)) }
      val useSni = sslParameters.map(sslParams => returning(sslParams.getType.getDeclaredField("useSni")) { _.setAccessible(true) })

      if (sslParameters.isEmpty) {
        // FIXME: this reflection hack still doesn't work on some devices,
        // we should check if assets work there and find better solution for them
        // logging to hockey to have statistics about unsupported devices
        HockeyApp.saveException(new Exception("ReflectionConfigurator - sslParameters not found"), "ClientWrapper could not configure SNI extension using reflection")
      }

      def apply(engine: SSLEngine, host: String, port: Int) = {
        peerHost.set(engine, host)
        peerPort.set(engine, port)
        for {
          sslParams <- sslParameters
          sni <- useSni
        } {
          sni.set(sslParams.get(engine), true)
        }
      }
    }

    val holders = new mutable.HashMap[Class[_], EngineHolder]

    override def configureEngine(engine: SSLEngine, data: AsyncHttpClientMiddleware.GetSocketData, host: String, port: Int): Unit = LoggedTry {
      holders.getOrElseUpdate(engine.getClass, new EngineHolder(engine.getClass)).apply(engine, host, port)
    }
  }
}
