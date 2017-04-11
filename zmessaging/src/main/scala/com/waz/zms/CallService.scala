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
package com.waz.zms

import android.content.{Context, Intent}
import com.waz.ZLog._
import com.waz.api.VoiceChannelState._
import com.waz.model.ConvId
import com.waz.model.VoiceChannelData.{ChannelState, ConnectionState}
import com.waz.service.call.CallInfo.IsActive
import com.waz.service.{Accounts, ZMessaging}
import com.waz.sync.ActivePush
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.EventContext

import scala.concurrent.{Future, Promise}

/**
 * Background service keeping track of ongoing calls to make sure ZMessaging is running as long as a call is active.
 */
class CallService extends FutureService {
  import com.waz.zms.CallService._

  implicit val ec = EventContext.Global

  lazy val executor = new CallExecutor(getApplicationContext, ZMessaging.currentAccounts)

  override protected def onIntent(intent: Intent, id: Int): Future[Any] = wakeLock.async {
    debug(s"onIntent $intent")
    if (intent != null && intent.hasExtra(ConvIdExtra)) {
      val convId = ConvId(intent.getStringExtra(ConvIdExtra))
      debug(s"convId: $convId")

      intent.getAction match {
        case ActionJoin => executor.join(convId, id, withVideo = false)
        case ActionJoinWithVideo => executor.join(convId, id, withVideo = true)
        case ActionLeave => executor.leave(convId, id)
        case ActionSilence => executor.silence(convId, id)
        case _ => executor.track(convId, id)
      }
    } else {
      error("missing intent extras")
      Future.successful({})
    }
  }
}

object CallService {
  private implicit val logTag: LogTag = logTagFor[CallService]
  val ConvIdExtra = "conv_id"

  val ActionTrack = "com.waz.zclient.call.ACTION_TRACK"
  val ActionJoin = "com.waz.zclient.call.ACTION_JOIN"
  val ActionJoinWithVideo = "com.waz.zclient.call.ACTION_JOIN_WITH_VIDEO"
  val ActionLeave = "com.waz.zclient.call.ACTION_LEAVE"
  val ActionSilence = "com.waz.zclient.call.ACTION_SILENCE"

  def apply(context: Context, conv: ConvId) = {
    if (context.startService(trackIntent(context, conv)) == null) {
      error(s"could not start CallService, make sure it's added to AndroidManifest")
    }
  }

  def intent(context: Context, conv: ConvId, action: String = ActionTrack) = {
    val intent = new Intent(context, classOf[CallService])
    intent.setAction(action)
    intent.putExtra(ConvIdExtra, conv.str)
  }

  def trackIntent(context: Context, conv: ConvId) = intent(context, conv, ActionTrack)

  def joinIntent(context: Context, conv: ConvId) = intent(context, conv, ActionJoin)
  def joinWithVideoIntent(context: Context, conv: ConvId) = intent(context, conv, ActionJoinWithVideo)

  def leaveIntent(context: Context, conv: ConvId) = intent(context, conv, ActionLeave)

  def silenceIntent(context: Context, conv: ConvId) = intent(context, conv, ActionSilence)
}

class CallExecutor(val context: Context, val accounts: Accounts)(implicit ec: EventContext) extends ActivePush {

  private implicit val logTag: LogTag = logTagFor[CallExecutor]
  import Threading.Implicits.Background

  def join(conv: ConvId, id: Int, withVideo: Boolean) = execute { zms =>
    isV3Call(zms).flatMap {
      case true => Future.successful(zms.calling.acceptCall(conv))
      case _ => zms.voice.joinVoiceChannel(conv, withVideo) flatMap (_ => track(conv, zms))
    }
  }(s"CallExecutor.join($id, withVideo = $withVideo)")

  def leave(conv: ConvId, id: Int) = execute { zms =>
    isV3Call(zms).flatMap {
      case true => Future.successful(zms.calling.endCall(conv))
      case _ => zms.voice.leaveVoiceChannel(conv)
    }
  }(s"CallExecutor.leave $id")

  def silence(conv: ConvId, id: Int) = execute { zms =>
    isV3Call(zms).flatMap {
      case true => Future.successful(zms.calling.endCall(conv))
      case _ => zms.voice.silenceVoiceChannel(conv)
    }
  }(s"CallExecutor.silence $id")

  def track(conv: ConvId, id: Int): Future[Unit] = execute(track(conv, _)) (s"CallExecutor.track $id")

  private def isV3Call(zms: ZMessaging) = zms.calling.currentCall.map { case IsActive() => true; case _ => false }.head

  /**
    * Sets up a cancellable future which will end the call after the `callConnectingTimeout`, unless
    * the promise is completed (which can be triggered by a successfully established call), at which
    * point the future will be cancelled, and the call allowed to continue indefinitely.
    */
  private def track(conv: ConvId, zms: ZMessaging): Future[Unit] = {
    val promise = Promise[Unit]()

    val timeoutFuture = CancellableFuture.delay(zms.timeouts.calling.callConnectingTimeout) flatMap { _ =>
      CancellableFuture.lift(isV3Call(zms)).flatMap {
        case true =>
          CancellableFuture.lift(zms.calling.currentCall.head.map(_.state).map(ChannelState.isConnecting).map {
            case true => zms.calling.endCall(conv)
            case _ =>
          })
        case _ =>
          CancellableFuture.lift(zms.voice.getVoiceChannel(conv) flatMap {
            case data if ChannelState.isConnecting(data.state) => zms.voice.leaveVoiceChannel(conv)
            case _ => Future.successful(())
          })
      }
    }

    def check() = isV3Call(zms).flatMap {
      case true =>
        zms.calling.currentCall.head map {
          case info if info.state == SELF_CALLING =>
            verbose(s"call in progress: $info")
          case _ => promise.trySuccess({})
        }
      case false =>
        zms.voice.getVoiceChannel(conv) map {
          case data if data.deviceActive =>
            verbose(s"call in progress: $data")
            if (data.deviceState == ConnectionState.Connected) timeoutFuture.cancel()
          case _ => promise.trySuccess({})
        }
      }

    val subscriberV2 = zms.voiceContent.activeChannel { _ => check() }
    val subscriberV3 = zms.calling.currentCall.map(_.state) { _ => check()}

    check()

    promise.future.onComplete { _ =>
      timeoutFuture.cancel()
      subscriberV2.destroy()
      subscriberV3.destroy()
    }
    promise.future
  }
}
