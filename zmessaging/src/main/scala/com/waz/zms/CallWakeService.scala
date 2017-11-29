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

import android.content.{Intent => AIntent}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model.{ConvId, UserId}
import com.waz.service.ZMessaging
import com.waz.service.call.CallInfo.CallState._
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.EventContext
import com.waz.utils.returning
import com.waz.utils.wrappers.{Context, Intent}

import scala.concurrent.Future

/**
 * Background service keeping track of ongoing calls to make sure ZMessaging is running as long as a call is active.
 */
class CallWakeService extends FutureService with ZMessagingService {
  import Threading.Implicits.Background
  import com.waz.zms.CallWakeService._
  implicit val ec = EventContext.Global

  override protected def onIntent(intent: AIntent, id: Int): Future[Any] = onZmsIntent(intent) { zms =>
    debug(s"onIntent $intent")
    if (intent != null && intent.hasExtra(ConvIdExtra)) {
      val convId = ConvId(intent.getStringExtra(ConvIdExtra))
      debug(s"convId: $convId")

      intent.getAction match {
        case ActionJoin => join(zms, convId, withVideo = false)
        case ActionJoinGroup => join(zms, convId, withVideo = false)
        case ActionJoinWithVideo => join(zms, convId, withVideo = true)
        case ActionJoinGroupWithVideo => join(zms, convId, withVideo = true)
        case ActionLeave => leave(zms, convId)
        case ActionSilence => silence(zms, convId)
        case _ => track(zms, convId)
      }
    } else {
      error("missing intent extras")
      Future.successful({})
    }
  }


  def join(zms: ZMessaging, conv: ConvId, withVideo: Boolean) = zms.calling.startCall(conv, withVideo)

  def leave(zms: ZMessaging, conv: ConvId) = zms.calling.endCall(conv)

  def silence(zms: ZMessaging, conv: ConvId) = zms.calling.endCall(conv)

  /**
    * Sets up a cancellable future which will end the call after the `callConnectingTimeout`, unless
    * the state changes to something else than SelfCalling and we don't end the call here
    */
  private def track(zms: ZMessaging, conv: ConvId): Future[Unit] = {
    val timeoutFuture = CancellableFuture.delay(zms.timeouts.calling.callConnectingTimeout) flatMap { _ =>
      CancellableFuture.lift(zms.calling.currentCall.head.collect{case Some(i) => i.state}.map(isConnectingStates.contains).map {
        case true => zms.calling.endCall(conv)
        case _ =>
      })
    }
    val state = zms.calling.currentCall.map(_.map(_.state))
    state.filter(!_.contains(SelfCalling)).head.map(_ => {}).andThen {
      case _ => timeoutFuture.cancel()
    }
  }
}

object CallWakeService {
  val ConvIdExtra = "conv_id"

  val ActionTrack = "com.waz.zclient.call.ACTION_TRACK"
  val ActionJoin = "com.waz.zclient.call.ACTION_JOIN"
  val ActionJoinGroup = "com.waz.zclient.call.ACTION_JOIN_GROUP"
  val ActionJoinWithVideo = "com.waz.zclient.call.ACTION_JOIN_WITH_VIDEO"
  val ActionJoinGroupWithVideo = "com.waz.zclient.call.ACTION_JOIN_GROUP_WITH_VIDEO"
  val ActionLeave = "com.waz.zclient.call.ACTION_LEAVE"
  val ActionSilence = "com.waz.zclient.call.ACTION_SILENCE"

  lazy val isConnectingStates = Set(SelfCalling, SelfJoining, OtherCalling)

  def apply(context: Context, user: UserId, conv: ConvId) = {
    if (!context.startService(trackIntent(context, user, conv))) {
      error(s"could not start CallService, make sure it's added to AndroidManifest")
    }
  }

  def intent(context: Context, user: UserId, conv: ConvId, action: String = ActionTrack) = {
    returning(Intent(context, classOf[CallWakeService])) { i =>
      i.setAction(action)
      i.putExtra(ConvIdExtra, conv.str)
      i.putExtra(ZMessagingService.ZmsUserIdExtra, user.str)
    }
  }

  def trackIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionTrack)

  def joinIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionJoin)
  def joinGroupIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionJoinGroup)
  def joinWithVideoIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionJoinWithVideo)
  def joinGroupWithVideoIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionJoinGroupWithVideo)

  def leaveIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionLeave)

  def silenceIntent(context: Context, user: UserId, conv: ConvId) = intent(context, user, conv, ActionSilence)
}
