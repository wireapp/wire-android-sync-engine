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
package com.waz.service.call


import android.content.Context
import com.sun.jna.Pointer
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.VideoSendState._
import com.waz.api.VoiceChannelState
import com.waz.api.VoiceChannelState._
import com.waz.api.impl.ErrorResponse
import com.waz.content.MembersStorage
import com.waz.model.otr.ClientId
import com.waz.model.{RConvId, _}
import com.waz.service.call.CallInfo.ClosedReason.{AnsweredElsewhere, Interrupted}
import com.waz.service.call.CallInfo._
import com.waz.service.call.Calling._
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.messages.MessagesService
import com.waz.service.push.PushService
import com.waz.service.{ErrorsService, EventScheduler, MediaManagerService, NetworkModeService}
import com.waz.sync.otr.OtrSyncHandler
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.jna.{Size_t, Uint32_t}
import com.waz.utils.{RichDate, RichInstant, returning}
import com.waz.zms.CallService
import org.threeten.bp.{Duration, Instant}

import scala.concurrent.{Future, Promise}

/**
  * Implementation notes:
  *  - The void *arg, here represented in the native methods as a Pointer, is a reference to the object that handles the callbacks and native
  *    method calls. Since we know which class is doing the calling and handling, we don't really need to use it and so pass and receive null
  *
  *  - All modifications to the current call state should be done INSIDE a future to the serial dispatch queue - best is to perform any
  *  mutations inside `withConvWhenReady` or `withConvFromPointer`. This should ensure that all state changes are performed serially to
  *  the currentCall state in the order the user/AVS triggers the callbacks.
  */
class CallingService(context:             Context,
                     selfUserId:          UserId,
                     clientId:            ClientId,
                     convs:               ConversationsContentUpdater,
                     membersStorage:      MembersStorage,
                     otrSyncHandler:      OtrSyncHandler,
                     flowManagerService:  FlowManagerService,
                     messagesService:     MessagesService,
                     mediaManagerService: MediaManagerService,
                     pushService:         PushService,
                     callLogService:      CallLogService,
                     network: NetworkModeService,
                     errors:              ErrorsService) {

  private implicit val eventContext = EventContext.Global
  private implicit val dispatcher = new SerialDispatchQueue(name = "CallingService")

  //need to ensure that flow manager and media manager are initialised for v3 (they are lazy values)
  private val fm = flowManagerService.flowManager
  private val mm = mediaManagerService.mediaManager

  val v3Available = Signal.future(Calling.v3Available.map(_ => true).recover { case _ => false })
  val currentCall = Signal(IdleCall)

  val requestedCallVersion = Signal(-1)

  currentCall.onChanged { i =>
    verbose(s"Calling information changed: $i")
    i.convId.foreach(CallService(context, _)) // start tracking
  }

  private val init = Calling.v3Available.map { _ =>

    val callingReady = Promise[Unit]()

    verbose(s"Initialising calling for self: $selfUserId and current client: $clientId")
    Calling.wcall_init(selfUserId.str, clientId.str,
      new ReadyHandler {
        override def invoke(version: Int, arg: Pointer): Unit = {
          verbose(s"Calling ready: avs version: $version")
          requestedCallVersion ! version
          callingReady.success(())
        }
      },
      new SendHandler {
        override def invoke(ctx: Pointer, convId: String, userId: String, clientId: String, data: Pointer, len: Size_t, arg: Pointer): Int = {
          val msg = data.getString(0, "UTF-8") //be sure to fetch the message on the callback thread!
          withConversationFromPointer(convId) { conv =>
            sendCallMessage(conv.id, GenericMessage(Uid(), GenericContent.Calling(msg)), ctx)
          }
          0
        }
      },
      new IncomingCallHandler {
        override def invoke(convId: String, userId: String, video_call: Boolean, arg: Pointer): Unit = withConversationFromPointer(convId) { conv =>
          val user = UserId(userId)
          verbose(s"Incoming call from $user in conv: $convId")
          currentCall.mutate {
            //Assume that when a video call starts, sendingVideo will be true. From here on, we can then listen to state handler
            case IsIdle() => CallInfo(Some(conv.id), user, Set(user), OTHER_CALLING, isVideoCall = video_call, videoSendState = if(video_call) PREVIEW else DONT_SEND)
            case cur =>
              verbose(s"Incoming call from $user while in a call - ignoring")
              cur
          }
        }
      },
      new MissedCallHandler {
        override def invoke(convId: String, msg_time: Uint32_t, userId: String, video_call: Boolean, arg: Pointer): Unit = {
          val t = instant(msg_time)
          verbose(s"Missed call for conversation: $convId at $t from user $userId. Video: $video_call")
          messagesService.addMissedCallMessage(RConvId(convId), UserId(userId), t)
        }
      },
      new AnsweredCallHandler {
        override def invoke(convId: String, arg: Pointer) = withConversationFromPointer(convId) { conv =>
          verbose(s"outgoing call answered for conv: ${conv.id}")
          currentCall.mutate {
            case call if call.convId.contains(conv.id) => call.copy(state = SELF_JOINING)
          }
        }
      },
      new EstablishedCallHandler {
        override def invoke(convId: String, userId: String, arg: Pointer): Unit = withConversationFromPointer(convId) { conv =>
          verbose(s"call established for conv: ${conv.id}, userId: $userId")
          currentCall.mutate{ c =>
            setVideoSendActive(conv.id, if (Seq(PREVIEW, SEND).contains(c.videoSendState)) true else false) //will upgrade call videoSendState
            c.copy(state = SELF_CONNECTED, estabTime = Some(Instant.now))
          }
        }
      },
      new CloseCallHandler {
        override def invoke(reasonCode: Int, convId: String, userId: String, metrics_json: String, arg: Pointer): Unit = withConversationFromPointer(convId) { conv =>
          currentCall.mutate { call =>
            onCallClosed(call, ClosedReason(reasonCode), conv, UserId(userId))
          }
        }
      },
      null
    )
    callingReady.future
  }.map { _ =>
    //Handles the state of received video, not sent video
    Calling.wcall_set_video_state_handler(new VideoStateHandler {
      override def invoke(state: Int, arg: Pointer): Unit = dispatcher { //ensure call state change is posted to dispatch queue
        verbose(s"video state changed: ${VideoReceiveState(state)}")
        currentCall.mutate(_.copy(videoReceiveState = VideoReceiveState(state)))
      }
    })
  }

  init.onFailure { case e =>
    error("Error initialising calling v3", e)
  }

  (for {
    c <- currentCall if c.state != NO_ACTIVE_USERS
    n <- network.networkMode
  } yield n).onChanged { _ =>
    init.map { _ =>
      verbose("network mode changed during call - informing AVS")
      Calling.wcall_network_changed()
    }
  }

  def startCall(convId: ConvId, isVideo: Boolean = false): Unit = withConvWhenReady(convId) { conv =>
    membersStorage.getByConv(conv.id).map { members =>
      verbose(s"startCall convId: $convId, isVideo: $isVideo")
      if (members.size == 2) {
        members.map(_.userId).find(_ != selfUserId).foreach { other =>
          Calling.wcall_start(conv.remoteId.str, isVideo) match {
            case 0 =>
              currentCall.mutate {
                case IsIdle() =>
                  //Assume that when a video call starts, sendingVideo will be true. From here on, we can then listen to state handler
                  CallInfo(Some(conv.id), selfUserId, Set(other), SELF_CALLING, isVideoCall = isVideo, videoSendState = if (isVideo) PREVIEW else DONT_SEND)
                case cur =>
                  error("Call already in progress, not updating")
                  cur
              }
            case err => warn(s"Unable to start call, reason: errno: $err")
          }
        }
      } else {
        warn("Group calls not yet supported in calling v3")
      }
    }
  }

  def endCall(convId: ConvId): Unit = withConvWhenReady(convId) { conv =>
    verbose(s"endCall: $convId")
    //wcall_end will always(???) lead to the CloseCall handler - we handle state there
    currentCall.mutate { call =>
      if (call.state == VoiceChannelState.OTHER_CALLING) {
        verbose("Incoming call ended - performing reject instead")
        Calling.wcall_reject(conv.remoteId.str)
        onCallClosed(call.copy(hangupRequested = true), ClosedReason.Normal, conv, call.caller)
      } else {
        verbose(s"Call ended in other state: ${call.state}, ending normally")
        Calling.wcall_end(conv.remoteId.str)
        call.copy(hangupRequested = true)
      }
    }
  }

  def continueDegradedCall(): Unit = currentCall.head.map(info => (info.convId, info.outstandingMsg, info.state) match {
    case (Some(cId), Some((msg, ctx)), _) => convs.storage.setUnknownVerification(cId).map(_ => sendCallMessage(cId, msg, ctx))
    case (Some(cId), None, OTHER_CALLING) => convs.storage.setUnknownVerification(cId).map(_ => acceptCall(cId))
    case _ => error(s"Tried resending message on invalid info: ${info.convId} in state ${info.state} with msg: ${info.outstandingMsg}")
  })

  private def sendCallMessage(convId: ConvId, msg: GenericMessage, ctx: Pointer): Unit = withConvWhenReady(convId) { conv =>
    verbose(s"Sending msg on behalf of avs: convId: $convId, msg: $msg")
    otrSyncHandler.postOtrMessage(conv, msg).map {
      case Right(_) =>
        currentCall.mutate(_.copy(outstandingMsg = None))
        init.map(_ => Calling.wcall_resp(200, "", ctx))
      case Left(ErrorResponse.Unverified) =>
        warn(s"Conversation degraded, delay sending message on behalf of AVS")
        //TODO need to handle degrading of conversation during a call
        //Currently, the call will just time out...
        currentCall.mutate(_.copy(outstandingMsg = Some(msg, ctx)))
      case Left(ErrorResponse(code, errorMsg, label)) =>
        init.map(_ => Calling.wcall_resp(code, errorMsg, ctx))
    }
  }

  //Drop the current call in case of incoming GSM interruption
  def onInterrupted(): Unit = {
    verbose("onInterrupted - gsm call received")
    currentCall.map(_.convId).currentValue.flatten.foreach { convId =>
      //Ensure that conversation state is only performed INSIDE withConvWhenReady
      withConvWhenReady(convId) { conv =>
        Calling.wcall_end(conv.remoteId.str)
        currentCall.mutate(_.copy(closedReason = Interrupted))
      }
    }
  }

  def acceptCall(convId: ConvId): Unit = withConvWhenReady(convId) { conv =>
    verbose(s"answerCall: $convId")
    Calling.wcall_answer(conv.remoteId.str)
    currentCall.mutate {
      case call if call.convId.contains(conv.id) => call.copy(state = SELF_JOINING)
    }
  }

  def setCallMuted(muted: Boolean): Unit = fm.foreach { f =>
    verbose(s"setCallMuted: $muted")
    currentCall.mutate {
      case c@IsActive() =>
        f.setMute(muted)
        c.copy(muted = muted)
      case c =>
        error("No active call, ignoring mute operation")
        c
    }
  }

  def setVideoSendActive(convId: ConvId, send: Boolean): Unit = {
    verbose(s"setVideoSendActive: $convId, $send")
    withConvWhenReady(convId) { conv =>
      Calling.wcall_set_video_send_active(conv.remoteId.str, send)
      currentCall.mutate(_.copy(videoSendState = if (send) SEND else DONT_SEND))
    }
  }

  def setAudioConstantBitRateEnabled(enabled: Int): Unit = {
    verbose(s"setting the audio cbr to $enabled")
    Calling.wcall_enable_audio_cbr(enabled)
  }

  val callMessagesStage = EventScheduler.Stage[CallMessageEvent] {
    case (_, events) => Future.successful(events.sortBy(_.time).foreach { e =>
      receiveCallEvent(e.content, e.time.instant, e.convId, e.from, e.sender)
    })
  }

  private def onCallClosed(currentCall: CallInfo, reason: ClosedReason, conv: ConversationData, userId: UserId): CallInfo = {
    verbose(s"call closed: reason: $reason, convId: ${conv.id}, userId: $userId")
    currentCall match {
      case call if call.convId.contains(conv.id) =>
        if (reason != AnsweredElsewhere) call.state match {
          //TODO do we want a small timeout before placing a "You called" message, in case of accidental calls? maybe 5 secs
          case SELF_CALLING =>
            verbose("Call timed out out the other didn't answer - add a \"you called\" message")
            messagesService.addMissedCallMessage(conv.id, selfUserId, Instant.now)
          case OTHER_CALLING | SELF_JOINING =>
            verbose("Call timed out out and we didn't answer - mark as missed call")
            messagesService.addMissedCallMessage(conv.id, userId, Instant.now)
          case SELF_CONNECTED =>
            verbose("Had a successful call, save duration as a message")
            call.estabTime.foreach { est =>
              messagesService.addSuccessfulCallMessage(conv.id, call.caller, est, est.until(Instant.now))
              //TODO can this information be gathered some other way - we really only care about successful calls.
              callLogService.addEstablishedCall(None, conv.id, call.isVideoCall)
            }
          case _ =>
            warn(s"Call closed from unexpected state: ${call.state}")
        }

        //Leave the current call with any information that makes sense to keep after a call was finished for tracking - will be overwritten on a new call.
        IdleCall.copy(
          convId          = call.convId,
          estabTime       = call.estabTime,
          caller          = call.caller,
          hangupRequested = call.hangupRequested,
          closedReason    = if (call.closedReason == Interrupted) Interrupted else reason
        )
      case call if call.convId.isDefined =>
        verbose("A call other than the current one was closed - likely missed another incoming call.")
        messagesService.addMissedCallMessage(conv.id, userId, Instant.now)
        //don't change the current call state, since the close callback was for a different conv/call
        call
      case _ =>
        warn("There was no current call defined - setting call information to idle")
        IdleCall
    }
  }

  private def receiveCallEvent(content: String, msgTime: Instant, convId: RConvId, from: UserId, sender: ClientId): Unit = {

    val drift = pushService.beDrift.currentValue.getOrElse(Duration.ZERO)
    val curTime = Instant.now + drift

    val msg = content.getBytes("UTF-8")
    verbose(s"Received msg for avs: localTime: ${Instant.now} curTime: $curTime, drift: $drift, msgTime: $msgTime, msg: $content")
    Calling.wcall_recv_msg(msg, msg.length, uint32_tTime(curTime), uint32_tTime(msgTime), convId.str, from.str, sender.str)
  }

  protected def uint32_tTime(instant: Instant) =
    returning(Uint32_t((Instant.now.toEpochMilli / 1000).toInt))(t => verbose(s"uint32_tTime for $instant = ${t.value}"))

  protected def instant(uint32_t: Uint32_t) = Instant.ofEpochMilli(uint32_t.value.toLong * 1000)

  /**
    * Conversation ids passed to and from AVS are always string representations of the REMOTE conv id. This convenience method
    * provides the conv data for each one.
    */
  private def withConversationFromPointer(convId: String)(f: ConversationData => Unit) = convs.convByRemoteId(RConvId(convId)).map {
    case Some(conv) => f(conv)
    case _ => error(s"Unknown conv: $convId")
  }

  //Ensures that wcall is initialised and then loads the conversation to perform any call actions on.
  private def withConvWhenReady(convId: ConvId)(f: ConversationData => Unit): Unit = init.flatMap(_ => convs.convById(convId).map {
    case Some(conv) => f(conv)
    case _ => error(s"Could not find conversation: $convId")
  }).recover {
    case e: Throwable => error("Attempted to perform avs v3 action after failed init", e)
  }
}


