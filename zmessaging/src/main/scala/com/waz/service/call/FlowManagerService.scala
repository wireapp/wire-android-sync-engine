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
import android.view.View
import com.waz.{Analytics, HockeyApp}
import com.waz.ZLog._
import com.waz.api._
import com.waz.call._
import com.waz.log.LogHandler
import com.waz.model._
import com.waz.service._
import com.waz.service.push.WebSocketClientService
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events._
import com.waz.zms.R
import com.waz.znet.ContentEncoder.BinaryRequestContent
import com.waz.znet.Response.ResponseBodyDecoder
import com.waz.znet.ResponseConsumer.ByteArrayConsumer
import com.waz.znet._
import org.json.{JSONException, JSONObject}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class FlowManagerService(context: Context, netClient: ZNetClient, websocket: WebSocketClientService, prefs: PreferenceService, network: NetworkModeService) {
  import FlowManagerService._

  val MetricsUrlRE = "/conversations/([a-z0-9-]*)/call/metrics/complete".r
  val avsAudioTestFlag: Long = 1 << 1

  private implicit val ev = EventContext.Global
  private implicit val dispatcher = new SerialDispatchQueue(name = "FlowManagerService")

  private lazy val loggingEnabledPrefKey = Try(context.getResources.getString(R.string.pref_avs_logging_key)).getOrElse("PREF_KEY_AVS_LOGGING") // hardcoded value used in tests
  private lazy val logLevelPrefKey       = Try(context.getResources.getString(R.string.pref_avs_loglevel_key)).getOrElse("PREF_KEY_AVS_LOGLEVEL")

  private lazy val metricsEnabledPref = prefs.analyticsEnabledPref
  private lazy val loggingEnabledPref = prefs.preferenceBooleanSignal(loggingEnabledPrefKey)
  private lazy val logLevelPref       = prefs.intPreference(logLevelPrefKey)

  val onMediaEstablished = new Publisher[RConvId]
  val onFlowManagerError = new Publisher[(RConvId, Int)] // (conv, errorCode)
  val onVolumeChanged = new Publisher[(RConvId, UserId, Float)] // (conv, participant, volume)

  val onFlowsEstablished = new Publisher[EstablishedFlows]

  val onFlowRequest = new Publisher[String]
  val onFlowResponse = new Publisher[String]
  val onFlowEvent = new Publisher[UnknownCallEvent]
  val onAvsMetricsReceived = EventStream[AvsMetrics]()

  val stateOfReceivedVideo = Signal[StateOfReceivedVideo](UnknownState)
  val cameraFailedSig = Signal[Boolean]

  val avsLogDataSignal = metricsEnabledPref.signal.zip(loggingEnabledPref.signal).zip(logLevelPref.signal) map { case ((metricsEnabled, loggingEnabled), logLevel) =>
      AvsLogData(metricsEnabled = metricsEnabled, loggingEnabled = loggingEnabled, AvsLogLevel.fromPriority(logLevel))
  }

  lazy val lastNetworkModeChange = Signal(Instant.MIN)
  lazy val lastWebsocketConnect = Signal(Instant.MIN)

  websocket.connected { if (_) lastWebsocketConnect ! Instant.now() }
  network.networkMode { _ => lastNetworkModeChange ! Instant.now() }

  lazy val aggregatedInstants = new AggregatingSignal[Instant, Option[(Instant, Instant)]](lastWebsocketConnect.onChanged, Future.successful(None), {
    case (None, a) => Some((a, a))
    case (Some((_, previous)), a) => Some((previous, a))
  }) zip lastNetworkModeChange

  aggregatedInstants {
    case (Some((previousConnect, currentConnect)), networkChange) if networkChange.isAfter(previousConnect) && networkChange.isBefore(currentConnect) =>
      debug(s"reconnected websocket after a network change: last connect at $previousConnect, current at $currentConnect, network change at $networkChange")
      doWithFlowManager(_.networkChanged())
    case other => () // nothing to do
  }

  @volatile private var callVolumePubs = Map[String, Publisher[Float]]()

  private var activeFlows = Set[RConvId]()

  private val responseDecoder = new ResponseBodyDecoder {
    override def apply(contentType: String, contentLength: Long) = new ByteArrayConsumer(contentLength, contentType)
  }

  // (protected instead of private just for testing)
  protected final val requestHandler = new RequestHandler {
    override def request(manager: FlowManager, path: String, method: String, ctype: String, content: Array[Byte], ctx: Long): Int = {
      val m = Option(method).getOrElse(Request.GetMethod)
      val t = Option(ctype).getOrElse("")
      val c = Option(content).getOrElse(Array())

      debug(s"request($path, $m, $t, ${new String(c)}, $ctx)")
      onFlowRequest ! s"$m $path"
      val startTime = System.currentTimeMillis

      path match {
        case MetricsUrlRE(convId) => onAvsMetricsReceived ! AvsMetrics(RConvId(convId), content)
        case _ => netClient(Request(m, Some(path), data = Some(BinaryRequestContent(c, t)), decoder = Some(responseDecoder))).map { resp =>
          onFlowResponse ! f"after ${System.currentTimeMillis - startTime}%4d ms - $m $path"
          resp match {
            case Response(status, BinaryResponse(data, mime), _) => doWithFlowManager(_.response(status.status, mime, data, ctx))
            case Response(status, EmptyResponse, _) => doWithFlowManager(_.response(status.status, null, null , ctx))
            case r => error(s"unexpected response $r for FlowManager request($path, $m, $t, ${new String(c)}, $ctx)")
          }
        } (dispatcher)
      }
      0
    }
  }


  lazy val flowManager: Option[FlowManager] = LoggedTry {
    val fm = new FlowManager(context, requestHandler, if (prefs.uiPreferences.getBoolean(prefs.autoAnswerCallPrefKey, false)) avsAudioTestFlag else 0)
    fm.addListener(flowListener)
    fm.setLogHandler(logHandler)
    metricsEnabledPref.signal { fm.setEnableMetrics }
    fm
  } .toOption

  val callEventsStage = EventScheduler.Stage[UnknownCallEvent] { (convId, events) =>
    flowManager.fold(Future.successful(())) { fm =>
      Future {
        events.foreach { event =>
          verbose(s"passing call event to flow manager: ${event.json}")
          onFlowEvent ! event
          fm.event("application/json", event.json.toString.getBytes("utf8"))
          verbose(s"event delivered: $event")
        }
      }
    }
  }

  private val logHandler = new LogHandler { // unused atm
    override def append(msg: String): Unit = ()
    override def upload(): Unit = ()
  }

  private val flowListener = new FlowManagerListener {
    override def volumeChanged(convId: String, participantId: String, volume: Float): Unit = {
      callVolumePubs.getOrElse(participantId, returning(new Publisher[Float]) { p =>
        callVolumePubs = callVolumePubs.updated(participantId, p)
        Signal.wrap(volume, p).throttle(500.millis) { volume =>  onVolumeChanged ! (RConvId(convId), UserId(participantId), volume) }
      }) ! volume
    }

    override def mediaEstablished(convId: String): Unit = onMediaEstablished ! RConvId(convId)

    override def handleError(convId: String, errCode: Int): Unit = {
      warn(s"FlowManager reported error: $errCode for conv: $convId")
      onFlowManagerError ! (RConvId(convId), errCode)
    }

    override def conferenceParticipants(convId: String, participantIds: Array[String]): Unit = {
      debug(s"avs conference participants for $convId: ${participantIds mkString ", "}")
      onFlowsEstablished ! EstablishedFlows(RConvId(convId), participantIds.toSet map UserId)
    }

    override def changeVideoState(state: Int, reason: Int): Unit =
      stateOfReceivedVideo ! returning(StateAndReason(AvsVideoState fromState state, reason = AvsVideoReason fromReason reason)) { s => debug(s"avs changeVideoState($s)") }

    //TODO coordinate removal with avs - not used anymore
    override def createVideoPreview(): Unit = ()

    //TODO coordinate removal with avs - not used anymore
    override def releaseVideoPreview(): Unit = ()

    //TODO coordinate removal with avs - not used anymore
    override def createVideoView(convId: String, partId: String): Unit = ()

    //TODO coordinate removal with avs - not used anymore
    override def releaseVideoView(convId: String, partId: String): Unit = ()

    override def changeAudioState(state: Int): Unit = {
      //TODO
    }

    override def cameraFailed(): Unit = {
      debug(s"cameraFailed")
      cameraFailedSig ! true
    }

    override def changeVideoSize(i: Int, i1: Int): Unit = ()
  }

  def acquireFlows(convId: RConvId, selfId: UserId, participantIds: Set[UserId], sessionId: Option[CallSessionId]): Future[Unit] = Future {
    debug(s"acquireFlows($convId), self: $selfId, participants: $participantIds, active: ${activeFlows(convId)}")
    flowManager.fold {
      Analytics.saveException(new IllegalStateException("flow manager not initialized"), s"Trying to acquire flows, but flowManager is not initialized, most likely due to native library loading issues")
    } { fm =>
      activeFlows += convId

      try {
        fm.setSelfUser(selfId.str)
        participantIds foreach (id => fm.addUser(convId.str, id.str, null)) // passing the user names to AVS is optional (only used for debugging), and we don't want to retrieve them in this place, so we pass "null"
      } catch { case e: NoSuchMethodError => warn("AVS version too old to support setting users when acquiring flows. Would need 1.10.+ for that. Ignoring.") }

      fm.acquireFlows(convId.toString, sessionId .map (_.toString) .getOrElse(""))
    }
  }

  def setFlowManagerMuted(muted: Boolean): Future[Unit] = Future {
    debug(s"setFlowManagerMuted($muted)")
    doWithFlowManager(_.setMute(muted))
  }

  def releaseFlows(convId: RConvId): Future[Unit] = Future {
    debug(s"releaseFlows($convId), isActive: ${activeFlows(convId)}")
    flowManager foreach { fm =>
      if (activeFlows(convId)) {
        fm.releaseFlows(convId.str)
        activeFlows -= convId
      }
    }
  }

  def setLoggingEnabled(enable: Boolean): Unit = loggingEnabledPref := enable
  def setLogLevel(logLevel: AvsLogLevel): Unit = {
    verbose(s"setLogLevel($logLevel)")
    logLevelPref := logLevel.priority
  }

  def getSortedGroupCallParticipantIds(unsortedIds: Array[String]): Array[String] = try {
    returning(FlowManager.sortConferenceParticipants(unsortedIds)) { sorted => debug(s"sorted participants: ${sorted mkString ", "}") }
  } catch {
    case e: Throwable =>
      error(s"participants sorting failed, falling back to lexical sort", e)
      unsortedIds.sorted
  }

  // tells us whether the remote side supports our sending our video stream for this call
  def canSendVideo(id: RConvId): Boolean = withFlowManager({ fm =>
    debug(s"canSendVideo($id)")
    safeguardAgainstOldAvs(fm.canSendVideo(id.str), fallback = false)
  }, fallback = false).getOrElse(false)

  // this should be called when the user presses the button to activate/deactivate the video stream for a call
  def setVideoSendState(id: RConvId, state: VideoSendState): Future[Unit] = schedule { fm =>
    debug(s"setVideoSendActive($id, $state)")
    safeguardAgainstOldAvs(fm.setVideoSendState(id.str, state.serialized), fallback = ())
  }

  def setBackground(b: Boolean): Future[Unit] = schedule { fm =>
    debug(s"setBackground($b)")
    safeguardAgainstOldAvs(fm.setBackground(b), fallback = ())
  }

  def getVideoCaptureDevices: Future[Vector[CaptureDeviceData]] = scheduleOr[Array[CaptureDevice]]({ fm =>
    debug("getVideoCaptureDevices")
    safeguardAgainstOldAvs(fm.getVideoCaptureDevices, fallback = Array.empty)
  }, Array.empty).map(_.map(d => CaptureDeviceData(d.devId, d.devName))(breakOut))

  def setVideoCaptureDevice(id: RConvId, deviceId: String): Future[Unit] = schedule { fm =>
    debug(s"setVideoCaptureDevice($id, $deviceId)")
    fm.setVideoCaptureDevice(id.str, deviceId)
  }

  // This is the preview of the outgoing video stream.
  // Call this from the callback telling us to.
  def setVideoPreview(view: View): Future[Unit] = schedule { fm =>
    debug(s"setVideoPreview($view)")
    cameraFailedSig ! false //reset this signal since we are trying to start the capture again
    fm.setVideoPreview(null, view)
  }

  // This is the incoming video call stream from the other participant.
  // partId is the participant id (for group calls, can be null for now).
  // Call this from the callback telling us to.
  def setVideoView(id: RConvId, partId: Option[UserId], view: View): Future[Unit] = schedule { fm =>
    debug(s"setVideoView($id, $partId, $view")
    fm.setVideoView(id.str, partId.map(_.str).orNull, view)
  }

  private def doWithFlowManager(op: FlowManager => Unit): Try[Unit] = withFlowManager(op, ())

  private def withFlowManager[T](op: FlowManager => T, fallback: => T): Try[T] =
    flowManager.fold { warn("unable to access flow manager"); LoggedTry(fallback) } { fm => LoggedTry { op(fm) } }

  private def schedule(op: FlowManager => Unit)(implicit dispatcher: ExecutionContext): Future[Unit] =
    scheduleWithoutRecovery(op) .recoverWithLog(reportHockey = true)

  private def scheduleOr[T](op: FlowManager => T, fallback: => T)(implicit dispatcher: ExecutionContext): Future[T] =
    scheduleWithoutRecovery(op) recover { LoggedTry.errorHandler(reportError = true) andThen (_.getOrElse(fallback)) }

  private def scheduleWithoutRecovery[T](op: FlowManager => T)(implicit dispatcher: ExecutionContext): Future[T] =
    flowManager.fold[Future[T]] { Future.failed(new IllegalStateException("unable to access flow manager")) } { fm => Future(op(fm)) (dispatcher) }

  private def safeguardAgainstOldAvs[T](op: => T, fallback: => T): T = try op catch {
    case e: Error =>
      warn("too old avs version")
      fallback
  }
}

case class AvsMetrics(protected[call] val rConvId: RConvId, private val bytes: Array[Byte]) {

  def convId = rConvId.str

  val json = try {
    new JSONObject(new String(bytes))
  } catch {
    case e: JSONException => new JSONObject()
  }

  var isVideoCall: Boolean = false
  var kindOfCall = KindOfCall.UNKNOWN

  override def toString = s"AvsMetrics($rConvId, isVideoCall: $isVideoCall, kindOfCall: $kindOfCall, ${json.toString})"
}

object FlowManagerService {
  private implicit val logTag: LogTag = logTagFor(FlowManagerService)

  case class AvsLogData(metricsEnabled: Boolean, loggingEnabled: Boolean, logLevel: AvsLogLevel)
  case class EstablishedFlows(convId: RConvId, users: Set[UserId])

  sealed trait StateOfReceivedVideo {
    def state: AvsVideoState
    def reason: AvsVideoReason
  }
  case class StateAndReason(state: AvsVideoState, reason: AvsVideoReason) extends StateOfReceivedVideo
  case object UnknownState extends StateOfReceivedVideo {
    override def state = AvsVideoState.STOPPED
    override def reason = AvsVideoReason.NORMAL
  }

  object AvsLogData {
    val Default = AvsLogData(metricsEnabled = false, loggingEnabled = false, AvsLogLevel.DEBUG)
  }
}
