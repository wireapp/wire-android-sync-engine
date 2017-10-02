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

import com.sun.jna.Pointer
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.{error, verbose}
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.call.Avs.WCall
import com.waz.service.call.Calling._
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.jna.{Size_t, Uint32_t}
import com.waz.utils.returning
import org.threeten.bp.Instant

import scala.concurrent.{Future, Promise}

trait Avs {
  def registerAccount(callingService: CallingService): Future[WCall]
  def unregisterAccount(wCall: WCall): Future[Unit]
  def onNetworkChanged(wCall: WCall): Future[Unit]
  def startCall(wCall: WCall, convId: RConvId, isVideoCall: Boolean, isGroup: Boolean): Future[Int]
  def answerCall(wCall: WCall, convId: RConvId): Unit
  def onHttpResponse(wCall: WCall, status: Int, reason: String, arg: Pointer): Future[Unit]
  def onConfigRequest(wCall: WCall, error: Int, json: String): Future[Unit]
  def onReceiveMessage(wCall: WCall, msg: String, currTime: Instant, msgTime: Instant, convId: RConvId, userId: UserId, clientId: ClientId): Unit
  def endCall(wCall: WCall, convId: RConvId): Unit
  def rejectCall(wCall: WCall, convId: RConvId): Unit
  def setVideoSendActive(wCall: WCall, convId: RConvId, active: Boolean): Unit
  //cbr = constant bit rate
  def enableAudioCbr(wCall: WCall, enabled: Boolean): Unit
}

/**
  * Facilitates synchronous communication with AVS and also provides a wrapper around the native code which can be easily
  * mocked for testing the CallingService
  */
class AvsImpl() extends Avs {

  private implicit val dispatcher = new SerialDispatchQueue(name = "AvsWrapper")

  import Avs._

  private val available = Calling.avsAvailable.map { _ =>
    returning(Calling.wcall_init()) { res =>
      verbose(s"AVS initialized: $res")
    }
  }.map(_ => {})

  override def registerAccount(cs: CallingService) = available.flatMap { _ =>
    verbose(s"Initialising calling for: ${cs.selfUserId} and current client: ${cs.clientId}")

    val callingReady = Promise[Unit]()

    val wCall = Calling.wcall_create(
      cs.selfUserId.str,
      cs.clientId.str,
      new ReadyHandler {
        override def onReady(version: Int, arg: Pointer) = {
          callingReady.success({})
        }
      },
      new SendHandler {
        override def onSend(ctx: Pointer, convId: String, userid_self: String, clientid_self: String, userid_dest: String, clientid_dest: String, data: Pointer, len: Size_t, transient: Boolean, arg: Pointer) = {
          cs.onSend(ctx, RConvId(convId), UserId(userid_dest), ClientId(clientid_dest), data.getString(0, "UTF-8"))
          0
        }
      },
      new IncomingCallHandler {
        override def onIncomingCall(convId: String, msg_time: Uint32_t, userId: String, video_call: Boolean, should_ring: Boolean, arg: Pointer) =
          cs.onIncomingCall(RConvId(convId), UserId(userId), video_call, should_ring)
      },
      new MissedCallHandler {
        override def onMissedCall(convId: String, msg_time: Uint32_t, userId: String, video_call: Boolean, arg: Pointer): Unit =
          cs.onMissedCall(RConvId(convId), instant(msg_time), UserId(userId), video_call)
      },
      new AnsweredCallHandler {
        override def onAnsweredCall(convId: String, arg: Pointer) = cs.onOtherSideAnsweredCall(RConvId(convId))
      },
      new EstablishedCallHandler {
        override def onEstablishedCall(convId: String, userId: String, arg: Pointer) =
          cs.onEstablishedCall(RConvId(convId), UserId(userId))
      },
      new CloseCallHandler {
        override def onClosedCall(reasonCode: Int, convId: String, msg_time: Uint32_t, userId: String, arg: Pointer) =
          cs.onClosedCall(ClosedReason(reasonCode), RConvId(convId), instant(msg_time), UserId(userId))
      },
      new MetricsHandler {
        override def onMetricsReady(convId: String, metricsJson: String, arg: Pointer) =
          cs.onMetricsReady(RConvId(convId), metricsJson)
      },
      new CallConfigRequestHandler {
        override def onConfigRequest(inst: WCall, arg: WCall): Int =
          cs.onConfigRequest(inst)
      },
      null
    )

    callingReady.future.map { _ =>
      //TODO it would be nice to convince AVS to move these methods into the method wcall_init.
      Calling.wcall_set_video_state_handler(wCall, new VideoReceiveStateHandler {
        override def onVideoReceiveStateChanged(state: Int, arg: Pointer) = cs.onVideoReceiveStateChanged(VideoReceiveState(state))
      })

      Calling.wcall_set_audio_cbr_enabled_handler(wCall, new BitRateStateHandler {
        override def onBitRateStateChanged(arg: Pointer) = cs.onBitRateStateChanged()
      })

      Calling.wcall_set_group_changed_handler(wCall, new GroupChangedHandler {
        override def onGroupChanged(convId: String, arg: Pointer) = {
          //TODO change this set to an ordered set to for special audio effects?
          val mStruct = wcall_get_members(wCall, convId)
          val members = if (mStruct.membc.intValue() > 0) mStruct.toArray(mStruct.membc.intValue()).map(u => UserId(u.userid)).toSet else Set.empty[UserId]
          wcall_free_members(mStruct.getPointer)
          cs.onGroupChanged(RConvId(convId), members)
        }
      })
      wCall
    }
  }

  private def withAvsReturning[A](onSuccess: => A, onFailure: => A): Future[A] = available.map(_ => onSuccess).recover {
    case err =>
      error("Tried to perform action on avs after it failed to initialise", err)
      onFailure
  }


  private def withAvs(f: => Unit): Future[Unit] = withAvsReturning(f, {})

  override def unregisterAccount(wcall: WCall) = withAvs(Calling.wcall_destroy(wcall))

  override def onNetworkChanged(wCall: WCall) = withAvs(Calling.wcall_network_changed(wCall))


  override def startCall(wCall: WCall, convId: RConvId, isVideoCall: Boolean, isGroup: Boolean) = withAvsReturning(wcall_start(wCall, convId.str, isVideoCall, isGroup), -1)

  override def answerCall(wCall: WCall, convId: RConvId) = withAvs(wcall_answer(wCall: WCall, convId.str))

  override def onHttpResponse(wCall: WCall, status: Int, reason: String, arg: Pointer) = withAvs(wcall_resp(wCall, status, reason, arg))

  override def onReceiveMessage(wCall: WCall, msg: String, currTime: Instant, msgTime: Instant, convId: RConvId, from: UserId, sender: ClientId) = {
    val bytes = msg.getBytes("UTF-8")
    withAvs(wcall_recv_msg(wCall, bytes, bytes.length, uint32_tTime(currTime), uint32_tTime(msgTime), convId.str, from.str, sender.str))
  }

  override def onConfigRequest(wCall: WCall, error: Int, json: String): Future[Unit] = withAvs(wcall_config_update(wCall, error, json))

  override def endCall(wCall: WCall, convId: RConvId) = withAvs(wcall_end(wCall, convId.str))

  override def rejectCall(wCall: WCall, convId: RConvId) = withAvs(wcall_reject(wCall, convId.str))

  override def setVideoSendActive(wCall: WCall, convId: RConvId, active: Boolean) = withAvs(wcall_set_video_send_active(wCall, convId.str, active))

  override def enableAudioCbr(wCall: WCall, enabled: Boolean) = withAvs(wcall_enable_audio_cbr(wCall, if (enabled) 1 else 0))

}

object Avs {

  type WCall = Pointer

  def instant(uint32_t: Uint32_t) = Instant.ofEpochMilli(uint32_t.value.toLong * 1000)

  def uint32_tTime(instant: Instant) =
    returning(Uint32_t((instant.toEpochMilli / 1000).toInt))(t => verbose(s"uint32_tTime for $instant = ${t.value}"))

  /**
    *   WCALL_REASON_NORMAL              0
    *   WCALL_REASON_ERROR               1
    *   WCALL_REASON_TIMEOUT             2
    *   WCALL_REASON_LOST_MEDIA          3
    *   WCALL_REASON_CANCELED            4
    *   WCALL_REASON_ANSWERED_ELSEWHERE  5
    *   WCALL_REASON_IO_ERROR            6
    *   WCALL_REASON_STILL_ONGOING       7
    *   WCALL_REASON_TIMEOUT_ECONN       8
    *   WCALL_REASON_DATACHANNEL         9
    *
    *   interrupted - SE only (when interrupted by GSM call)
    */
  type ClosedReason = ClosedReason.Value
  object ClosedReason extends Enumeration {
    val Normal, Error, Timeout, LostMedia, Canceled, AnsweredElsewhere, IOError, StillOngoing, TimeoutEconn, DataChannel, Interrupted = Value
  }

  /**
    *   WCALL_VIDEO_RECEIVE_STOPPED  0
    *   WCALL_VIDEO_RECEIVE_STARTED  1
    *   WCALL_VIDEO_RECEIVE_BAD_CONN 2
    *   Unknown - internal state     3
    */
  type VideoReceiveState = VideoReceiveState.Value
  object VideoReceiveState extends Enumeration {
    val Stopped, Started, BadConnection, Unknown = Value
  }
}
