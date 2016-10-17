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
package com.waz.threading

import java.util.Timer
import java.util.concurrent.{Executor, ExecutorService, Executors}

import android.os.{Handler, HandlerThread, Looper}
import com.waz.ZLog.error

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

object Threading {

  object Implicits {
    implicit val Background: DispatchQueue = Threading.ThreadPool
    implicit val Ui: DispatchQueue = Threading.Ui
    implicit val Image: DispatchQueue = Threading.ImageDispatcher
    implicit val BlockingIO: ExecutionContext = Threading.BlockingIO
  }

  var AssertsEnabled = true //to be set by application

  val Cpus = math.max(2, Runtime.getRuntime.availableProcessors())

  def executionContext(service: ExecutorService): ExecutionContext = new ExecutionContext {
    override def reportFailure(cause: Throwable): Unit = error(cause.getMessage, cause)("MainThreadPool")
    override def execute(runnable: Runnable): Unit = service.execute(runnable)
  }

  /**
   * Thread pool for non-blocking background tasks.
   */
  val ThreadPool: DispatchQueue = new LimitedDispatchQueue(Cpus, executionContext(Executors.newCachedThreadPool()), "CpuThreadPool")

  /**
   * Thread pool for blocking IO tasks.
   */
  val IOThreadPool: DispatchQueue = new LimitedDispatchQueue(Cpus, executionContext(Executors.newCachedThreadPool()), "IoThreadPool")

  val Background = ThreadPool

  val IO = IOThreadPool

  val BlockingIO: ExecutionContext = new ExecutionContext {
    val delegate = ExecutionContext.fromExecutor(null: Executor) // default impl that handles block contexts correctly
    override def execute(runnable: Runnable): Unit = delegate.execute(new Runnable {
        override def run(): Unit = blocking(runnable.run())
      })
    override def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)
  }

  lazy val Ui = new UiDispatchQueue

  val Timer = new Timer(true)

  Timer.purge()

  /**
   * Image decoding/encoding dispatch queue. This operations are quite cpu intensive, we don't want them to use all cores (leaving one spare core for other tasks).
   */
  val ImageDispatcher = new LimitedDispatchQueue(Cpus - 1, ThreadPool, "ImageDispatcher")

  lazy val BackgroundHandler: Future[Handler] = {
    val looper = Promise[Looper]
    val looperThread = new HandlerThread("BackgroundHandlerThread") {
      override def onLooperPrepared(): Unit = looper.success(getLooper)
    }
    looperThread.start()
    looper.future.map(new Handler(_))(Background)
  }

  def assertUiThread(): Unit = if (AssertsEnabled && (Thread.currentThread ne Looper.getMainLooper.getThread)) throw new AssertionError(s"Should be run on Ui thread, but is using: ${Thread.currentThread().getName}")
  def assertNotUiThread(): Unit = if (AssertsEnabled && (Thread.currentThread eq Looper.getMainLooper.getThread)) throw new AssertionError(s"Should be run on background thread, but is using: ${Thread.currentThread().getName}")
}
