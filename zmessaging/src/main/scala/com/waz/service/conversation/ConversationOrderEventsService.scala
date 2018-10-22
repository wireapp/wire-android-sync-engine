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
import com.waz.ZLog._
import com.waz.content.ConversationStorage
import com.waz.model.GenericContent._
import com.waz.model._
import com.waz.service.EventScheduler.Stage
import com.waz.service.messages.MessagesService
import com.waz.service.{EventPipeline, EventScheduler, UserService}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._

import scala.concurrent.Future

class ConversationOrderEventsService(selfUserId: UserId,
                                     convs:    ConversationsContentUpdater,
                                     storage:  ConversationStorage,
                                     messages: MessagesService,
                                     users:    UserService,
                                     sync:     SyncServiceHandle,
                                     pipeline: EventPipeline) {

  private implicit val dispatcher = new SerialDispatchQueue(name = "ConversationEventsDispatcher")

  private[service] def shouldChangeOrder(event: ConversationEvent): Boolean =
    event match {
      case _: CreateConversationEvent => true
      case _: CallMessageEvent        => true
      case _: OtrErrorEvent           => true
      case _: GenericAssetEvent       => true
      case _: ConnectRequestEvent     => true
      case _: OtrMessageEvent         => true
      case MemberJoinEvent(_, _, _, added, _) if added.contains(selfUserId)   => true
      case MemberLeaveEvent(_, _, _, leaving) if leaving.contains(selfUserId) => true
      case GenericMessageEvent(_, _, _, GenericMessage(_, content)) =>
        content match {
          case _: Asset               => true
          case _: Calling             => true
          case _: Cleared             => false
          case _: ClientAction        => false
          case _: Receipt             => false
          case _: Ephemeral           => true
          case _: AvailabilityStatus  => false
          case _: External            => true
          case _: ImageAsset          => true
          case _: Knock               => true
          case _: LastRead            => false
          case _: Location            => true
          case _: MsgRecall           => false
          case _: MsgEdit             => false
          case _: MsgDeleted          => false
          case _: Reaction            => false
          case _: Text                => true
          case _                      => false
        }
      case _ => false
    }

  private[service] def shouldUnarchive(event: ConversationEvent): Boolean =
    event match {
      case MemberLeaveEvent(_, _, _, leaving) if leaving contains selfUserId => false
      case _ => shouldChangeOrder(event)
    }

  val conversationOrderEventsStage: Stage.Atomic = EventScheduler.Stage[ConversationEvent] { (convId, es) =>

    val orderChanges    = processConversationOrderEvents(convId, es.filter(shouldChangeOrder))
    val unarchiveConvs  = processConversationUnarchiveEvents(convId, es.filter(shouldUnarchive))

    for {
      _ <- orderChanges
      _ <- unarchiveConvs
    } yield {}
  }

  def handlePostConversationEvent(event: ConversationEvent): Future[Unit] = {
    debug(s"handlePostConversationEvent($event)")
    Future.sequence(Seq(
      event match {
        case ev: MessageEvent => pipeline(Seq(ev.withCurrentLocalTime())) // local time is required for the hot knock mechanism
        case _ => Future.successful(())
      },

      convs.convByRemoteId(event.convId) flatMap {
        case Some(conv) =>
          convs.updateConversationLastRead(conv.id, event.time) map { _ => Future.successful(()) }
        case _ => Future.successful(())
      }
    )) map { _ => () }
  }

  private def processConversationOrderEvents(convId: RConvId, es: Seq[ConversationEvent]) =
    if (es.isEmpty) Future.successful(())
    else convs.processConvWithRemoteId(convId, retryAsync = true) { conv =>
      verbose(s"processConversationOrderEvents($conv, $es)")
      val lastTime = es.maxBy(_.time).time
      val fromSelf = es.filter(_.from == selfUserId)
      val lastRead = if (fromSelf.isEmpty) None else Some(fromSelf.maxBy(_.time).time)

      for {
        _ <- convs.updateLastEvent(conv.id, lastTime)
        _ <- lastRead match {
          case None => Future successful None
          case Some(time) => convs.updateConversationLastRead(conv.id, time)
        }
      } yield ()
    }

  private def processConversationUnarchiveEvents(convId: RConvId, events: Seq[ConversationEvent]) = {
    verbose(s"processConversationUnarchiveEvents($convId, ${events.size} events)")
    def unarchiveTime(es: Traversable[ConversationEvent]) = {
      val all = es.filter(shouldUnarchive)
      if (all.isEmpty) None
      else Some((all.maxBy(_.time).time, all.exists(unarchiveMuted), all.exists(hasMention)))
    }

    val convs = events.groupBy(_.convId).mapValues(unarchiveTime).filter(_._2.isDefined)

    storage.getByRemoteIds(convs.keys) flatMap { convIds =>
      storage.updateAll2(convIds, { conv =>
        convs.get(conv.remoteId).flatten match {
          case Some((time, unarchiveMuted, hasMention)) if conv.archiveTime.isBefore(time) && (conv.isAllAllowed || unarchiveMuted || (conv.onlyMentionsAllowed && hasMention)) =>
            conv.copy(archived = false, archiveTime = time)
          case _ =>
            conv
        }
      })
    }
  }

  private def unarchiveMuted(event: Event): Boolean = event match {
    case GenericMessageEvent(_, _, _, GenericMessage(_, _: Knock)) => true
    case _ => false
  }

  private def hasMention(event: Event): Boolean = event match {
    case GenericMessageEvent(_, _, _, GenericMessage(_, Text(_, mentions, _, _))) =>
      mentions.exists(_.userId.contains(selfUserId))
    case _ => false
  }
}
