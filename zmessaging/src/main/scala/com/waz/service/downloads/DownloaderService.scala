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
package com.waz.service.downloads

import android.content.Context
import com.waz.ZLog._
import com.waz.api.NetworkMode
import com.waz.api.ProgressIndicator.State
import com.waz.api.impl.ProgressIndicator
import com.waz.api.impl.ProgressIndicator.{Callback, ProgressData}
import com.waz.cache.{CacheEntry, CacheService, Expiration}
import com.waz.model.CacheKey
import com.waz.service.{NetworkModeService, PreferenceServiceImpl}
import com.waz.threading.CancellableFuture.CancelException
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils._
import com.waz.utils.events.{AggregatingSignal, EventContext, EventStream, Signal}
import com.waz.zms.R

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Keeps track of all download requests in priority queue, executes more important downloads first.
 * Priority is based on request start and cancel times.
 * Cancelling request doesn't cancel actual download, just postpones it for background download later.
 * Most of the time, requested asset will be needed in a future, even if current request is cancelled.
 *
 * TODO: implement download pause/resume (with range headers),
 * with this we could pause less important requests when new request is added
 * we could then remove background downloads limit, which is currently used to make sure there is
 * some bandwidth reserved for important requests
 */
class DownloaderService(context: Context, cache: CacheService, prefs: PreferenceServiceImpl, network: NetworkModeService) {
  import DownloaderService._
  private implicit val dispatcher = new SerialDispatchQueue(name = "DownloaderService")
  private implicit val ev = EventContext.Global

  private val downloads = new mutable.HashMap[CacheKey, DownloadEntry]()
  private val active = new mutable.HashSet[CacheKey]
  private val queue = new DownloadQueue

  private val onAdded = EventStream[DownloadEntry]()

  private lazy val downloadPrefAlways = Try(context.getResources.getString(R.string.zms_image_download_default_value)).getOrElse("always")
  private lazy val downloadPrefKey = Try(context.getResources.getString(R.string.zms_image_download_preference_key)).getOrElse("zms_pref_image_download") // hardcoded value used in tests

  private lazy val downloadPref = prefs.preference[String](downloadPrefKey, downloadPrefAlways, prefs.uiPreferences).signal
  private lazy val downloadEnabled = Signal.or(downloadPref.map(_ == downloadPrefAlways), network.networkMode.map(_ == NetworkMode.WIFI))

  downloadEnabled.disableAutowiring()

  def getDownload(key: CacheKey): Signal[Option[DownloadEntry]] =
    new AggregatingSignal[DownloadEntry, Option[DownloadEntry]](onAdded.filter(_.req.cacheKey == key), Future(downloads.get(key)), { (_, added) => Some(added) })

  def getDownloadState(key: CacheKey): Signal[ProgressData] = getDownload(key) flatMap {
    case Some(download) => download.state
    case None => Signal(ProgressData.Unknown)
  }

  def cancel(key: CacheKey): Future[Unit] = Future {
    downloads.remove(key) foreach { _.cancel() }
    active.remove(key)
    checkQueue()
  }

  def download[A <: DownloadRequest](req: A, force: Boolean = false, withRetries: Boolean = true)
                                    (implicit loader: Downloader[A]): CancellableFuture[Option[CacheEntry]] =
    if (withRetries) downloadWithRetries(req, force) else downloadOnce(req, force)

  private def downloadWithRetries[A <: DownloadRequest](req: A, force: Boolean = false, retry: Int = 0)
                                    (implicit loader: Downloader[A]): CancellableFuture[Option[CacheEntry]] = {
    val cf = downloadOnce(req, force)
    cf.flatMap {
      case None if retry >= DownloaderService.backoff.maxRetries => CancellableFuture.successful(None)
      case None => CancellableFuture.delay(DownloaderService.backoff.delay(retry)).flatMap { _ => downloadWithRetries(req, force, retry + 1)(loader) }
      case _ => cf // if everything is ok we want to return the original cancellable future, not a new one
    }
  }

  // 'protected' for the sake of unit tests, to enable mocking
  protected def downloadOnce[A <: DownloadRequest](req: A, force: Boolean = false)
                                        (implicit loader: Downloader[A],
                                         expires: Expiration = DownloaderService.DefaultExpiryTime
                                        ): CancellableFuture[Option[CacheEntry]] = {
    val p = Promise[Option[CacheEntry]]()
    p.tryCompleteWith(downloadEntry(req, loader, force).flatMap(_.promise.future))

    new CancellableFuture(p) {
      override def cancel()(implicit tag: LogTag): Boolean = {
        onListenerCancelled(req)
        super.cancel()(tag)
      }
    }

  }

  private def downloadEntry[A <: DownloadRequest](req: A, loader: Downloader[A], force: Boolean = false)(implicit expires: Expiration): Future[DownloadEntry] = Future {
    verbose(s"downloadEntry($req, $force)")

    def createEntry = returning(new DownloadEntry(req, loader.load(req, _))) { onAdded ! _ }

    returning(downloads.getOrElseUpdate(req.cacheKey, createEntry)) { entry =>
      entry.listenersCount += 1
      if (!active(req.cacheKey)) {
        entry.force = entry.force || force
        entry.time = System.currentTimeMillis()
        entry.expiration = Expiration(entry.expiration.timeout max expires.timeout)
        verbose("adding entry to the queue")
        queue.put(req, uiWaiting = true, entry.time)
        checkQueue()
      }
    }
  }

  private def onListenerCancelled(req: DownloadRequest) = Future {
    downloads.get(req.cacheKey) foreach { entry =>
      entry.listenersCount -= 1
      if (entry.listenersCount == 0 && !active(req.cacheKey))
        queue.put(req, uiWaiting = false, entry.time)
    }
  }

  private def checkQueue(): Unit = {
    verbose(s"checkQueue(), active: $active")

    def shouldStartNext = queue.peek().flatMap(req => downloads.get(req.cacheKey)).fold(false) { entry =>
      entry.listenersCount > 0 && active.size < MaxConcurrentDownloads || active.size < MaxBackgroundDownloads
    }

    while(shouldStartNext) {
      queue.poll() foreach { req =>
        downloads.get(req.cacheKey).fold(error(s"no download entry found for: $req")) { entry =>
          verbose(s"starting download for $entry")
          if (active(req.cacheKey)) error(s"entry was already active: $entry")
          active += req.cacheKey
          doDownload(entry) onComplete { res =>
            verbose(s"download complete for: $req, result: $res")
            entry.promise.tryComplete(res)
            downloads -= req.cacheKey
            active -= req.cacheKey
            checkQueue()
          }
        }
      }
    }
  }

  private def doDownload(download: DownloadEntry): CancellableFuture[Option[CacheEntry]] = {
    verbose(s"doDownload($download)")

    def actualDownload = {
      verbose(s"actualDownload $download")
      download.doDownload(download.state ! _)
    }

    def done(state: State) = {
      verbose(s"done($state), $download")
      download.state ! ProgressData(0, 0, state)
      downloads.remove(download.req.cacheKey)
      active -= download.req.cacheKey
      checkQueue()
    }

    if (download.force || downloadEnabled.currentValue.exists(identity)) {
      download.state ! ProgressData(0, 0, State.RUNNING)

      val result = actualDownload

      result.onComplete {
        case Success(Some(entry)) => done(State.COMPLETED)
        case Success(None) => done(State.FAILED)
        case Failure(ex: CancelException) =>
          download.state ! ProgressData(0, 0, State.CANCELLED)
          active -= download.req.cacheKey
          queue.put(download.req, uiWaiting = false, download.time)
          checkQueue()
        case Failure(ex) =>
          error(s"download failed for: $download", ex)
          done(State.FAILED)
      }

      result
    } else CancellableFuture.failed(DownloadOnWifiOnlyException)
  }
}

object DownloaderService {

  case object DownloadOnWifiOnlyException extends Exception("Downloading is disabled by download preference.")

  private implicit val tag: LogTag = logTagFor[DownloaderService]

  val MaxConcurrentDownloads = 4
  // number of concurrent downloads for request not immediately required by UI
  val MaxBackgroundDownloads = 1
  val DefaultExpiryTime = 7.days

  private var backoff = new ExponentialBackoff(250.millis, 5.minutes)
  def setBackoff(backoff: ExponentialBackoff) = this.backoff = backoff

  private[downloads] class DownloadEntry(val req: DownloadRequest, load: ProgressIndicator.Callback => CancellableFuture[Option[CacheEntry]]) {
    val promise = Promise[Option[CacheEntry]]()
    var force: Boolean = false
    var time = System.currentTimeMillis()
    var listenersCount = 0
    val state = Signal(ProgressData.Unknown)
    var expiration: Expiration = Duration.Zero
    @volatile var downloadFuture = CancellableFuture successful Option.empty[CacheEntry]

    def cancel(): Unit = {
      downloadFuture.cancel()
      promise.tryFailure(new CancelException("Cancelled by user"))
    }

    def doDownload(callback: Callback) =
      if (promise.isCompleted) new CancellableFuture(promise)
      else returning(load(callback)) { downloadFuture = _ }

    override def toString: LogTag = s"DownloadEntry($req) { force: $force, listeners: $listenersCount, state: $state, time: $time }"
  }
}
