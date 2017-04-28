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
package com.waz.mocked

import android.content.Context
import com.waz.api.VideoSendState
import com.waz.model.{CallSessionId, CaptureDeviceData, RConvId, UserId}
import com.waz.service.call.DefaultFlowManagerService
import com.waz.service.push.WebSocketClientService
import com.waz.service.{DefaultNetworkModeService, PreferenceServiceImpl}
import com.waz.threading.SerialDispatchQueue
import com.waz.znet.ZNetClient

import scala.concurrent.Future

class MockedFlowManagerService(context: Context, netClient: ZNetClient, websocket: WebSocketClientService, prefs: PreferenceServiceImpl, network: DefaultNetworkModeService)
  extends DefaultFlowManagerService(context, netClient, websocket, prefs, network) { self =>

  private implicit val dispatcher = new SerialDispatchQueue(name = "MockedFlowManagerService")

  var flowsMuted: Boolean = false
  var currentFlows: Set[RConvId] = Set.empty
  var sessionIdUsedToAcquireFlows: Option[CallSessionId] = None
  var convsThatCanSendVideo: Set[RConvId] = Set.empty
  var convsThatSendVideo: Map[RConvId, VideoSendState] = Map.empty
  var videoCaptureDeviceId = Map.empty[RConvId, String]

  override def acquireFlows(convId: RConvId, selfId: UserId, participantIds: Set[UserId], sessionId: Option[CallSessionId]): Future[Unit] = Future {
    sessionIdUsedToAcquireFlows = sessionId
    currentFlows += convId
  }

  override def setFlowManagerMuted(muted: Boolean): Future[Unit] = Future { flowsMuted = muted }
  override def releaseFlows(convId: RConvId): Future[Unit] = Future { currentFlows -= convId }
  override def canSendVideo(convId: RConvId): Boolean = convsThatCanSendVideo contains convId
  override def setVideoSendState(id: RConvId, state: VideoSendState): Future[Unit] = Future { convsThatSendVideo += id -> state }
  override def getVideoCaptureDevices = Future.successful(Vector(CaptureDeviceData("front", "front-facing cam"), CaptureDeviceData("back", "back-facing cam")))
  override def setVideoCaptureDevice(id: RConvId, deviceId: String): Future[Unit] = Future.successful { videoCaptureDeviceId += id -> deviceId }

  override lazy val flowManager = None
}
