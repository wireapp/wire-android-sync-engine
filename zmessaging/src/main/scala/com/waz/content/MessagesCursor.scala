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
package com.waz.content

import android.database.Cursor
import android.support.v4.util.LruCache
import com.waz.Analytics
import com.waz.ZLog._
import com.waz.content.MessagesCursor.Entry
import com.waz.db.{Reader, ReverseCursorIterator}
import com.waz.model._
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils._
import com.waz.utils.events.EventStream
import org.threeten.bp.Instant

import scala.collection.Searching.{Found, InsertionPoint}
import scala.concurrent.{Await, Future}

trait MsgCursor {
  def size: Int
  def lastReadIndex: Int
  def lastReadTime: Instant
  def apply(index: Int): MessageAndLikes
  def indexOf(time: Instant): Int
  def close(): Unit
}

class MessagesCursor(conv: ConvId, cursor: Cursor, override val lastReadIndex: Int, val lastReadTime: Instant, loader: MessageAndLikesStorage) extends MsgCursor { self =>
  import MessagesCursor._
  import com.waz.utils.events.EventContext.Implicits.global

  import scala.concurrent.duration._

  private implicit val dispatcher = new SerialDispatchQueue(name = "MessagesCursor")

  private val messages = new LruCache[MessageId, MessageAndLikes](WindowSize * 2)
  private val windowLoader = new WindowLoader(cursor)

  val createTime = Instant.now() //used in UI

  override def size = cursor.getCount

  val onUpdate = EventStream[MessageId]()

  private val subs = Seq (
    loader.onUpdate { m =>
      if (messages.get(m) != null) loader(Seq(m)).foreach(_.foreach{ mAndL =>
        messages.put(m, mAndL)
        onUpdate ! m
      })
    }
  )

  verbose(s"init($conv, _, $lastReadIndex, $lastReadTime) - lastRead: $lastReadIndex")

  override def close(): Unit = {
    Threading.assertUiThread()
    subs foreach(_.destroy())
    Future(if (! cursor.isClosed) cursor.close())
  }

  override def finalize: Unit = Future(if (! cursor.isClosed) cursor.close())

  def prefetchById(id: MessageId): Future[Unit] = {
    verbose(s"prefetchById($id)")
    for {
      m <- loader(Seq(id))
      i <- m.headOption.fold(Future successful lastReadIndex) { d => asyncIndexOf(d.message.time) }
      _ <- prefetch(i)
    } yield ()
  }

  /** will block if message is outside of prefetched window */
  override def indexOf(time: Instant): Int =
    returning(LoggedTry(Await.result(asyncIndexOf(time), 10.seconds)).getOrElse(lastReadIndex)) { index =>
      verbose(s"indexOf($time) = $index, lastReadTime: $lastReadTime")
    }

  private def asyncIndexOf(time: Instant): Future[Int] =
    windowLoader.currentWindow flatMap (_.indexOf(time) match {
      case index if index >= 0 => Future.successful(index)
      case _ =>
        Future {
          logTime(s"time: $time not found in pre-fetched window, had to go through cursor") {
            val indexFromEnd = new ReverseCursorIterator(cursor)(Entry.EntryReader).indexWhere(!_.time.isAfter(time))
            val index = if (indexFromEnd < 0) -1 else cursor.getCount - indexFromEnd - 1

            verbose(s"index in cursor: $index")
            if (index < 0) lastReadIndex else index
          }
        }
    })

  def prefetch(index: Int): Future[Unit] = windowLoader(index) flatMap { prefetch }

  def prefetch(window: IndexWindow): Future[Unit] = Future(window.msgs.iterator.filter(m => messages.get(m.id) == null).map(_.id).toVector) flatMap { ids =>
    if (ids.isEmpty) {
      verbose(s"prefetch at offset ${window.offset} unnecessary")
      Future.successful(())
    } else {
      val time = System.nanoTime()
      loader(ids) .map { ms =>
        ms foreach { m => messages.put(m.message.id, m) }
        verbose(s"pre-fetched ${ids.size} ids, got ${ms.size} msgs, for window offset: ${window.offset} in: ${(System.nanoTime() - time) / 1000 / 1000f} ms")
      } (Threading.Background) // LruCache is thread-safe, this mustn't be blocked by UI processing
    }
  }

  private var prevWindow = new IndexWindow(0, IndexedSeq.empty)

  private def loadWindow(index: Int) = {
    Threading.assertUiThread()

    if (index < 0 || index >= size)
      throw new IndexOutOfBoundsException(s"invalid message index: $index, available count: $size")

    val windowFuture = windowLoader(index)

    if (windowFuture.value.isDefined && windowFuture.value.get.isSuccess) windowFuture.value.get.get
    else logTime(s"loading window for index: $index")(Await.result(windowFuture, 5.seconds))
  }

  /** Returns message at given index, will block if message data is not yet available for given index. */
  override def apply(index: Int): MessageAndLikes = { // implementation note: avoid all allocations on the happy path
    val window = loadWindow(index)

    if (! window.contains(index)) {
      Analytics.saveException(new RuntimeException(s"cursor window loading failed, requested index: $index, got window with offset: ${window.offset} and size: ${window.msgs.size}"), "")
      MessageAndLikes.Empty
    } else {
      val fetching = if (prevWindow != window) {
        verbose(s"prefetching at $index, offset: ${window.offset}")
        prevWindow = window
        prefetch(window)
      } else futureUnit

      val id = window(index).id

      val msg = messages.get(id)
      if (msg ne null) msg else {
        logTime("waiting for window to prefetch")(Await.result(fetching, 5.seconds))
        Option(messages.get(id)).getOrElse {
          logTime(s"loading message for id: $id, position: $index") {
            val m = LoggedTry(Await.result(loader(Seq(id)), 500.millis).headOption).toOption.flatten
            m foreach { messages.put(id, _) }
            m.getOrElse(MessageAndLikes.Empty)
          }
        }
      }
    }
  }

  def getEntries(offset: Int, count: Int): Seq[Entry] = {
    val end = math.min(size, offset + count)
    val window = loadWindow(offset + count / 2)

    if (! window.contains(offset) || !window.contains(end - 1))
      Analytics.saveException(new RuntimeException(s"cursor window loading failed, requested [$offset, $end), got window with offset: ${window.offset} and size: ${window.msgs.size}"), "")

    window.msgs.slice(offset - window.offset, end - window.offset)
  }
}

object MessagesCursor {
  private implicit val tag: LogTag = "MessagesCursor"
  val WindowSize = 256
  val WindowMargin = WindowSize / 4
  val futureUnit = Future.successful(())

  val Empty: MsgCursor = new MsgCursor {
    override val size: Int = 0
    override val lastReadIndex: Int = 0
    override def lastReadTime: Instant = Instant.EPOCH
    override def indexOf(time: Instant): Int = -1
    override def apply(index: Int): MessageAndLikes = throw new IndexOutOfBoundsException(s"invalid index $index in empty message cursor")
    override def close(): Unit = ()
  }

  case class Entry(id: MessageId, time: Instant) {
    def <(e: Entry) = Entry.Order.compare(this, e) < 0
  }

  object Entry {
    val Empty = new Entry(MessageId(""), Instant.EPOCH)

    implicit object Order extends Ordering[Entry] {
      override def compare(x: Entry, y: Entry): Int = {
        if (x.time == y.time) Ordering.String.compare(x.id.str, y.id.str)
        else Ordering.Long.compare(x.time.toEpochMilli, y.time.toEpochMilli)
      }
    }

    implicit object EntryReader extends Reader[Entry] {
      override def apply(implicit c: Cursor): Entry =
        Entry(MessageId(c.getString(0)), Instant.ofEpochMilli(c.getLong(1)))
    }

    def apply(c: Cursor): Entry = EntryReader(c)
    def apply(m: MessageData): Entry = Entry(m.id, m.time)
  }
}

class WindowLoader(cursor: Cursor)(implicit dispatcher: SerialDispatchQueue) {
  import MessagesCursor._
  private implicit val tag = logTagFor[WindowLoader]

  @volatile private[this] var window = IndexWindow.Empty
  @volatile private[this] var windowFuture = Future.successful(window)
  @volatile private[this] var windowLoading = windowFuture

  private def shouldRefresh(window: IndexWindow, index: Int) =
    window == IndexWindow.Empty || window.offset > 0 && index < window.offset + WindowMargin || index > window.offset + WindowSize - WindowMargin

  private def fetchWindow(index: Int) = {
    verbose(s"fetchWindow($index)")
    val items = (index until math.min(cursor.getCount, index + MessagesCursor.WindowSize)) map { pos =>
      if (cursor.moveToPosition(pos)) Entry(cursor) else {
        error(s"can not move cursor to position: $pos, requested fetchWindow($index)")
        Entry.Empty
      }
    }
    IndexWindow(index, items)
  }

  private def loadWindow(index: Int) = windowLoading .recover { case _ => window } .map {
    case w if shouldRefresh(w, index) =>
      window = fetchWindow(math.max(0, index - WindowSize / 2))
      windowFuture = Future.successful(window)
      window
    case w => w
  }

  def apply(index: Int): Future[IndexWindow] = {
    if (shouldRefresh(window, index)) windowLoading = loadWindow(index)

    if (window.contains(index)) windowFuture else windowLoading
  }

  def currentWindow = windowFuture
}

case class IndexWindow(offset: Int, msgs: IndexedSeq[Entry]) {

  def contains(index: Int) = index >= offset && index < offset + msgs.size

  def apply(pos: Int) = msgs(pos - offset)

  def indexOf(time: Instant) = msgs.binarySearch(time, _.time) match {
    case Found(n) => n + offset
    case InsertionPoint(n) => if (n == 0 || n == msgs.size) -1 else n + offset
  }
}

object IndexWindow {
  val Empty = new IndexWindow(-1, IndexedSeq.empty)
}
