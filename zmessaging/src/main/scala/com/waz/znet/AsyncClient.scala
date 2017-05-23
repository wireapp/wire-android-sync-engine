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

import java.net.{ConnectException, UnknownHostException}
import java.util.concurrent.TimeoutException

import com.koushikdutta.async.http._
import com.koushikdutta.async.http.callback.HttpConnectCallback
import com.waz.HockeyApp.NoReporting
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.threading.CancellableFuture.CancelException
import com.waz.threading.{CancellableFuture, SerialDispatchQueue, Threading}
import com.waz.utils.returning
import com.waz.utils.wrappers.URI
import com.waz.znet.ContentEncoder.MultipartRequestContent
import com.waz.znet.Response.{DefaultResponseBodyDecoder, ResponseBodyDecoder}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal

class AsyncClient(bodyDecoder: ResponseBodyDecoder = DefaultResponseBodyDecoder,
                  val userAgent: String = AsyncClient.userAgent(),
                  val wrapper: Future[ClientWrapper] = ClientWrapper(),
                  requestWorker: RequestWorker = new KoushiRequestWorker,
                  responseWorker: ResponseWorker = new KoushiResponseWorker
                 ) {
  import AsyncClient._

  protected implicit val dispatcher = new SerialDispatchQueue(Threading.ThreadPool)

  def apply(uri: URI, request: Request[_]): CancellableFuture[Response] = {
    val body = request.getBody
    debug(s"Starting request[${request.httpMethod}]($uri) with body: '${if (body.toString.contains("password")) "<body>" else body}', headers: '${request.headers}'")

    val requestTimeout = if (request.httpMethod != Request.PostMethod) request.timeout else body match {
      case _: MultipartRequestContent => MultipartPostTimeout
      case _ => request.timeout
    }

    CancellableFuture.lift(wrapper) flatMap { client =>
      val p = Promise[Response]()
      @volatile var cancelled = false
      @volatile var processFuture = None: Option[CancellableFuture[_]]
      @volatile var lastNetworkActivity: Long = System.currentTimeMillis
      @volatile var timeoutForPhase = requestTimeout
      val interval = 5.seconds min request.timeout

      val requestBuilt = requestWorker.processRequest(uri, request.httpMethod, body, request.headers, request.followRedirect, timeoutForPhase)

      val httpFuture = client.execute(requestBuilt, new HttpConnectCallback {
        override def onConnectCompleted(ex: Exception, response: AsyncHttpResponse): Unit = {
          debug(s"Connect completed for uri: '$uri', ex: '$ex', cancelled: $cancelled")
          timeoutForPhase = request.timeout

          if (ex != null) {
            p.tryFailure(if (cancelled) CancellableFuture.DefaultCancelException else ex)
          } else {
            val networkActivityCallback = () => lastNetworkActivity = System.currentTimeMillis
            val future = responseWorker.processResponse(uri, response, request.decoder.getOrElse(bodyDecoder), request.downloadCallback, networkActivityCallback)
            p.tryCompleteWith(future)

            // XXX: order is important here, we first set processFuture and then check cancelled to avoid race condition in cancel callback
            processFuture = Some(future)
            if (cancelled) {
              debug("cancelled == true, cancelling...")
              future.cancel()
            }
          }
        }
      })

      val httpFuture = client.execute(request, callback)

      returning(new CancellableFuture(p) {
        override def cancel()(implicit tag: LogTag): Boolean = {
          debug(s"cancelling request for $uri")(tag)
          cancelled = true
          httpFuture.cancel()(tag)
          processFuture.foreach(_.cancel()(tag))
          super.cancel()(tag)
        }
      }.recover(exceptionStatus)) { cancellable =>
        def cancelOnInactivity: CancellableFuture[Unit] = {
          val timeSinceLastNetworkActivity = System.currentTimeMillis - lastNetworkActivity
          val t = timeoutForPhase
          if (timeSinceLastNetworkActivity > t.toMillis) CancellableFuture.successful {
            debug(s"cancelling due to inactivity: $timeSinceLastNetworkActivity")
            cancellable.fail(new TimeoutException("[AsyncClient] timedOut") with NoReporting)
          } else CancellableFuture.delay(interval min (t - timeSinceLastNetworkActivity.millis)) flatMap { _ => cancelOnInactivity }
        }

        val cancelOnTimeout = CancellableFuture.delay(interval) flatMap { _ => cancelOnInactivity }
        cancellable.onComplete { _ => cancelOnTimeout.cancel() }
      }
    }
  }

  def close(): Unit = wrapper foreach { _.stop() }

}

object AsyncClient {

  val MultipartPostTimeout = 15.minutes
  val DefaultTimeout = 30.seconds
  val EmptyHeaders = Map[String, String]()

  val UserAgentHeader = "User-Agent"
  val ContentTypeHeader = "Content-Type"

  def userAgent(appVersion: String = "*", zmsVersion: String = "*") = {
    import android.os.Build._
    s"Wire/$appVersion (zms $zmsVersion; Android ${VERSION.RELEASE}; $MANUFACTURER $MODEL)"
  }

  private def exceptionStatus: PartialFunction[Throwable, Response] = {
    case e: ConnectException => Response(Response.ConnectionError(e.getMessage))
    case e: UnknownHostException => Response(Response.ConnectionError(e.getMessage))
    case e: ConnectionClosedException => Response(Response.ConnectionError(e.getMessage))
    case e: ConnectionFailedException => Response(Response.ConnectionError(e.getMessage))
    case e: RedirectLimitExceededException => Response(Response.ConnectionError(e.getMessage))
    case e: TimeoutException => Response(Response.ConnectionError(e.getMessage))
    case e: CancelException => Response(Response.Cancelled)
    case NonFatal(e) => Response(Response.InternalError(e.getMessage, Some(e)))
  }

}
