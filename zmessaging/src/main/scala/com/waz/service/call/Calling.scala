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

import com.sun.jna.{Callback, Native, Pointer}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.utils.jna.{Size_t, Uint32_t}

import scala.concurrent.Promise

object Calling {

  private val available = Promise[Unit]()
  val v3Available = available.future

  try {
    verbose("Native.register: avs")
    Native.register(Calling.getClass, "avs")
    available.success({})
  }
  catch {
    case e: Throwable =>
      error("Unable to start avs", e)
      available.failure(e)
  }

  @native def wcall_network_changed(): Unit

  @native def wcall_init(userid: String, clientid: String, readyh: Callback, sendh: Callback, incomingh: Callback, missedh: Callback, answeredh: Callback, estabh: Callback, closeh: Callback, arg: Pointer): Int

  @native def wcall_close(): Unit

  @native def wcall_start(convid: String, is_video_call: Boolean): Int

  @native def wcall_answer(convid: String): Unit

  @native def wcall_resp(status: Int, reason: String, arg: Pointer): Int

  @native def wcall_recv_msg(msg: Array[Byte], len: Int, curr_time: Uint32_t, msg_time: Uint32_t, convId: String, userId: String, clientId: String): Int

  @native def wcall_end(convId: String): Unit

  @native def wcall_reject(convId: String): Unit

  @native def wcall_set_video_state_handler(wcall_video_state_change_h: VideoStateHandler): Unit

  @native def wcall_set_video_send_active(convid: String, active: Boolean): Unit

  @native def wcall_is_video_call(convid: String): Int

  @native def wcall_get_state(convid: String): Int

  @native def wcall_enable_audio_cbr(enabled: Int): Unit

  val WCALL_REASON_NORMAL             = 0
  val WCALL_REASON_ERROR              = 1
  val WCALL_REASON_TIMEOUT            = 2
  val WCALL_REASON_LOST_MEDIA         = 3
  val WCALL_REASON_CANCELED           = 4
  val WCALL_REASON_ANSWERED_ELSEWHERE = 5

  val WCALL_VIDEO_RECEIVE_STOPPED  = 0
  val WCALL_VIDEO_RECEIVE_STARTED  = 1
  val WCALL_VIDEO_RECEIVE_BAD_CONN = 2


  val WCALL_STATE_NONE        = 0
  val WCALL_STATE_OUTGOING    = 1
  val WCALL_STATE_INCOMING    = 2
  val WCALL_STATE_ANSWERED    = 3
  val WCALL_STATE_MEDIA_ESTAB = 4
  val WCALL_STATE_TERMINATING = 5
  val WCALL_STATE_UNKNOWN     = 6

  /* This will be called when the calling system is ready for calling.
     * The version parameter specifies the config obtained version to use
     * for calling.
     */
  trait ReadyHandler extends Callback {
    def invoke(version: Int, arg: Pointer): Unit
  }

  /* Send calling message otr data */
  trait SendHandler extends Callback {
    def invoke(ctx: Pointer, convId: String, userId: String, clientId: String, data: Pointer, len: Size_t, arg: Pointer): Int
  }

  /* Incoming call */
  trait IncomingCallHandler extends Callback {
    def invoke(convid: String, userid: String, video_call: Boolean, arg: Pointer): Unit
  }

  /* Missed call */
  trait MissedCallHandler extends Callback {
    def invoke(convId: String, msg_time: Uint32_t, userId: String, video_call: Boolean, arg: Pointer): Unit
  }

  trait AnsweredCallHandler extends Callback {
    def invoke(convId: String, arg: Pointer): Unit
  }

  /* Call established (with media) */
  trait EstablishedCallHandler extends Callback {
    def invoke(convId: String, userId: String, arg: Pointer): Unit
  }

  /* Call terminated */
  trait CloseCallHandler extends Callback {
    def invoke(reason: Int /* see constants above */ , convid: String, userid: String, metrics_json: String, arg: Pointer): Unit
  }

  /**
    * Callback used to inform user that received video has started
    * or stopped
    *
    * @param state  New video state start/stopped
    * @param arg    The handler argument passed when registering
    */
  trait VideoStateHandler extends Callback {
    def invoke(state: Int, arg: Pointer): Unit
  }

}
