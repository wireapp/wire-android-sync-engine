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
import java.util.concurrent.atomic.AtomicLong

import android.net.Uri
import com.koushikdutta.async._
import com.koushikdutta.async.callback.CompletedCallback.NullCompletedCallback
import com.koushikdutta.async.callback.DataCallback.NullDataCallback
import com.koushikdutta.async.callback.{CompletedCallback, DataCallback}
import com.koushikdutta.async.http._
import com.koushikdutta.async.http.callback.HttpConnectCallback
import com.waz.Analytics.NoReporting
import com.waz.ZLog._
import com.waz.api
import com.waz.api.impl.ProgressIndicator
import com.waz.threading.CancellableFuture.CancelException
import com.waz.threading.{CancellableFuture, SerialDispatchQueue, Threading}
import com.waz.utils.returning
import com.waz.znet.ContentEncoder.{MultipartRequestContent, _}
import com.waz.znet.Request.ProgressCallback
import com.waz.znet.Response.{DefaultResponseBodyDecoder, Headers, HttpStatus, ResponseBodyDecoder}
import com.waz.znet.ResponseConsumer.ConsumerState.Done

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class AsyncClient(bodyDecoder: ResponseBodyDecoder = DefaultResponseBodyDecoder, val userAgent: String = AsyncClient.userAgent(), wrapper: ClientWrapper = ClientWrapper) {
  import AsyncClient._

  protected implicit val dispatcher = new SerialDispatchQueue(Threading.ThreadPool)

  val client = wrapper(new AsyncHttpClient(new AsyncServer))

  def apply(uri: Uri, method: String = Request.GetMethod, body: RequestContent = EmptyRequestContent, headers: Map[String, String] = EmptyHeaders, followRedirect: Boolean = true, timeout: FiniteDuration = DefaultTimeout, decoder: Option[ResponseBodyDecoder] = None, downloadProgressCallback: Option[ProgressCallback] = None): CancellableFuture[Response] = {
    debug(s"Starting request[$method]($uri) with body: '${if (body.toString.contains("password")) "<body>" else body}', headers: '$headers'")

    val requestTimeout = if (method != Request.PostMethod) timeout else body match {
      case _: MultipartRequestContent => MultipartPostTimeout
      case _ => timeout
    }

    CancellableFuture.lift(client) flatMap { client =>
      val p = Promise[Response]()
      @volatile var cancelled = false
      @volatile var processFuture = None: Option[CancellableFuture[_]]
      @volatile var lastNetworkActivity: Long = System.currentTimeMillis
      @volatile var timeoutForPhase = requestTimeout
      val interval = 5.seconds min timeout

      val httpFuture = client.execute(buildHttpRequest(uri, method, body, headers, followRedirect, timeoutForPhase), new HttpConnectCallback {
        override def onConnectCompleted(ex: Exception, response: AsyncHttpResponse): Unit = {
          debug(s"Connect completed for uri: '$uri', ex: '$ex', cancelled: $cancelled")
          timeoutForPhase = timeout

          if (ex != null) {
            p.tryFailure(if (cancelled) CancellableFuture.DefaultCancelException else ex)
          } else {
            val networkActivityCallback = () => lastNetworkActivity = System.currentTimeMillis
            val future = processResponse(uri, response, decoder.getOrElse(bodyDecoder), downloadProgressCallback, networkActivityCallback)
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

      returning(new CancellableFuture(p) {
        override def cancel()(implicit tag: LogTag): Boolean = {
          debug(s"cancelling request for $uri")(tag)
          cancelled = true
          httpFuture.cancel(true)
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
            cancellable.cancel()("[AsyncClient] timedOut cancel")
          } else CancellableFuture.delay(interval min (t - timeSinceLastNetworkActivity.millis)) flatMap { _ => cancelOnInactivity }
        }

        val cancelOnTimeout = CancellableFuture.delay(interval) flatMap { _ => cancelOnInactivity }
        cancellable.onComplete { _ => cancelOnTimeout.cancel() }
      }
    }
  }

  def close(): Unit = client foreach { _.getServer.stop() }

  private def buildHttpRequest(uri: Uri, method: String, body: RequestContent, headers: Map[String, String], followRedirect: Boolean, timeout: FiniteDuration): AsyncHttpRequest = {
    val r = new AsyncHttpRequest(uri.normalizeScheme(), method)
    r.setTimeout(timeout.toMillis.toInt)
    r.setFollowRedirect(followRedirect)
    r.getHeaders.set(UserAgentHeader, userAgent)
    headers.foreach(p => r.getHeaders.set(p._1, p._2.trim))
    body(r)
  }

  //XXX: has to be executed on Http thread (inside onConnectCompleted), since data callbacks have to be set before this callback completes,
  private def processResponse(uri: Uri, response: AsyncHttpResponse, decoder: ResponseBodyDecoder = bodyDecoder, progressCallback: Option[ProgressCallback], networkActivityCallback: () => Unit): CancellableFuture[Response] = {
    val httpStatus = HttpStatus(response.code(), response.message())
    val contentLength = HttpUtil.contentLength(response.headers())
    val contentType = Option(response.headers().get(ContentTypeHeader)).getOrElse("")

    debug(s"got connection response for request: $uri, status: '$httpStatus', length: '$contentLength', type: '$contentType'")

    progressCallback foreach (_(ProgressIndicator.ProgressData(0L, contentLength, api.ProgressIndicator.State.RUNNING)))
    if (contentLength == 0) {
      progressCallback foreach { cb => Future(cb(ProgressIndicator.ProgressData(0, 0, api.ProgressIndicator.State.COMPLETED))) }
      CancellableFuture.successful(Response(httpStatus, headers = new Headers(response.headers())))
    } else {
      debug(s"waiting for content from $uri")

      val p = Promise[Response]()
      val consumer = decoder(contentType, contentLength)

      def onComplete(ex: Exception) = {
        response.setDataCallback(new NullDataCallback)
        response.setEndCallback(new NullCompletedCallback)
        p.tryComplete(
          if (ex != null) Failure(ex)
          else consumer.result match {
            case Success(body) =>
              progressCallback foreach { cb => Future(cb(ProgressIndicator.ProgressData(contentLength, contentLength, api.ProgressIndicator.State.COMPLETED))) }
              Success(Response(httpStatus, body, new Headers(response.headers())))

            case Failure(t) =>
              progressCallback foreach { cb => Future(cb(ProgressIndicator.ProgressData(0, contentLength, api.ProgressIndicator.State.FAILED))) }
              Success(Response(Response.InternalError(s"Response body consumer failed for request: $uri", Some(t), Some(httpStatus))))
          }
        )
      }

      response.setDataCallback(new DataCallback {
        val bytesSent = new AtomicLong(0L)

        override def onDataAvailable(emitter: DataEmitter, bb: ByteBufferList): Unit = {
          debug(s"data received for $uri, length: ${bb.remaining}")
          val numConsumed = bb.remaining
          val state = consumer.consume(bb)

          networkActivityCallback()
          progressCallback foreach { cb => Future(cb(ProgressIndicator.ProgressData(bytesSent.addAndGet(numConsumed), contentLength, api.ProgressIndicator.State.RUNNING))) }

          state match {
            case Done =>
              // consumer doesn't need any more data, we can stop receiving and report success
              debug(s"consumer [$consumer] returned Done, finishing response processing for: $uri")
              onComplete(null)
              response.close()
            case _ => // ignore
          }
        }
      })

      response.setEndCallback(new CompletedCallback {
        override def onCompleted(ex: Exception): Unit = {
          debug(s"response for $uri ENDED, ex: $ex, p.isCompleted: ${p.isCompleted}")
          Option(ex) foreach { error(s"response for $uri failed", _) }
          networkActivityCallback()
          onComplete(ex)
        }
      })

      new CancellableFuture(p) {
        override def cancel()(implicit tag: LogTag): Boolean = {
          debug(s"cancelling response processing for: $uri")(tag)
          response.setDataCallback(new NullDataCallback)
          response.setEndCallback(new NullCompletedCallback)
          response.close()
          progressCallback foreach { cb => Future(cb(ProgressIndicator.ProgressData(0, contentLength, api.ProgressIndicator.State.CANCELLED))) }
          super.cancel()(tag)
        }
      }
    }
  }
}

object AsyncClient {
  private implicit val logTag: LogTag = logTagFor[AsyncClient]
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
