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
package com.waz.service.conversation

import com.waz.ZLog.ImplicitTag._
import com.waz.content.GlobalPreferences.BackendDrift
import com.waz.content.{ConversationStorage, GlobalPreferences}
import com.waz.model._
import com.waz.service.AccountsService.InForeground
import com.waz.service.ZMessaging.clock
import com.waz.service._
import com.waz.sync.SyncServiceHandle
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.RichFuture.processSequential
import com.waz.utils.events.{AggregatingSignal, EventContext, EventStream}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

class TypingService(userId:        UserId,
                    conversations: ConversationStorage,
                    timeouts:      Timeouts,
                    accounts:      AccountsService,
                    sync:          SyncServiceHandle,
                    prefs:         GlobalPreferences) {
  import timeouts.typing._

  private implicit val ev = EventContext.Global
  private implicit val dispatcher = new SerialDispatchQueue(name = "TypingService")
  private val beDriftPref = prefs.preference(BackendDrift)

  private var typing: ConvId Map IndexedSeq[TypingUser] = Map().withDefaultValue(Vector.empty)

  private var selfIsTyping: Option[(ConvId, Long)] = None

  private var stopTypingTimeout: CancellableFuture[Unit] = CancellableFuture.successful(())
  private var refreshIsTyping: CancellableFuture[Unit] = CancellableFuture.successful(())

  val onTypingChanged = EventStream[(ConvId, IndexedSeq[TypingUser])]()

  val typingEventStage = EventScheduler.Stage[TypingEvent]((c, es) => processSequential(es)(handleTypingEvent))

  accounts.accountState(userId).on(dispatcher) {
    case InForeground => // fine
    case _            => stopTyping()
  }

  def typingUsers(conv: ConvId) = new AggregatingSignal[IndexedSeq[TypingUser], IndexedSeq[UserId]](onTypingChanged.filter(_._1 == conv).map(_._2), Future { typing(conv).map(_.id) }, { (_, updated) => updated.map(_.id) })

  def handleTypingEvent(e: TypingEvent): Future[Unit] =
    isRecent(e).flatMap {
      case true =>
        conversations.getByRemoteId(e.convId) map {
          case Some(conv) => setUserTyping(conv.id, e.from, e.time, e.isTyping)
          case None => // warn(s"Conversation ${e.convId} not found, ignoring.") TODO reintroduce when ids can be obfuscated
        }
      case _ => Future.successful(())
    }


  def selfChangedInput(conv: ConvId): Future[Unit] = Future {
    stopTypingTimeout.cancel()
    selfIsTyping = Some((conv, clock.millis))
    if (refreshIsTyping.isCompleted) postIsTyping(conv)
    stopTypingTimeout = CancellableFuture.delayed(stopTimeout) { stopTyping(conv) }
  }

  def selfClearedInput(conv: ConvId): Future[Unit] = Future {
    stopTypingTimeout.cancel()
    stopTyping(conv)
  }

  private def stopTyping(conv: ConvId): Unit = selfIsTyping match {
    case Some((`conv`, _)) =>
      refreshIsTyping.cancel()
      selfIsTyping = None
      sync.postTypingState(conv, typing = false)
    case _ => ()
  }

  private def stopTyping(): Unit = selfIsTyping foreach {
    case (conv, _) =>
      stopTypingTimeout.cancel()
      refreshIsTyping.cancel()
      selfIsTyping = None
      sync.postTypingState(conv, typing = false)
  }

  private def postIsTyping(conv: ConvId): Unit = {
    sync.postTypingState(conv, typing = true)
    refreshIsTyping = CancellableFuture.delayed(refreshDelay) { postIsTyping(conv) }
  }

  def getTypingUsers(conv: ConvId): CancellableFuture[IndexedSeq[UserId]] = dispatcher { typing(conv) map (_.id) }

  def isSelfTyping(conv: ConvId): CancellableFuture[Boolean] = dispatcher { selfIsTyping.exists(_._1 == conv) }

  private def setUserTyping(conv: ConvId, user: UserId, time: Instant, isTyping: Boolean): Unit = {
    val current = typing(conv)
    current.find(_.id == user).foreach(_.cleanUp.cancel())

    if (!isTyping && current.exists(_.id == user)) {
      typing += conv -> current.filterNot(user == _.id)
      onTypingChanged ! (conv -> typing(conv))
    } else if (isTyping) {
      val cleanUp = CancellableFuture.delayed(receiverTimeout - (clock.millis - time.toEpochMilli).millis) {
        setUserTyping(conv, user, clock.instant(), isTyping = false)
      }
      val idx = current.indexWhere(_.id == user)
      if (idx == -1) {
        typing += conv -> (current :+ TypingUser(user, time, cleanUp))
        onTypingChanged ! (conv -> typing(conv))
      } else {
        typing += conv -> current.updated(idx, TypingUser(user, time, cleanUp))
      }
    }
  }

  def isRecent(event: TypingEvent): Future[Boolean] = beDriftPref.apply().map { beDrift =>
    val now = clock.instant().plus(beDrift).toEpochMilli
    now - event.time.toEpochMilli < receiverTimeout.toMillis
  }
}

case class TypingUser(id: UserId, time: Instant, cleanUp: CancellableFuture[Unit])
