package com.waz.service.push

import com.localytics.android.Localytics
import com.waz.HockeyApp
import com.waz.ZLog.verbose
import com.waz.model.GcmTokenRemoveEvent
import com.waz.service.{EventScheduler, PreferenceService}
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.utils.wrappers.GoogleApi
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.util.control.NonFatal

class PushTokenService(googleApi: GoogleApi,
                       prefs:     PreferenceService) {

  import PushTokenService._

  implicit val dispatcher = new SerialDispatchQueue(name = "PushTokenDispatchQueue")

  private implicit val ev = EventContext.Global

  import prefs._

  val lastReceivedConvEventTime = preference[Instant]("last_received_conv_event_time", Instant.EPOCH)
  val lastFetchedConvEventTime  = preference[Instant]("last_fetched_conv_event_time",  Instant.ofEpochMilli(1))
  val lastFetchedLocalTime      = preference[Instant]("last_fetched_local_time",       Instant.EPOCH)
  val lastRegistrationTime      = preference[Instant]("push_registration_time",        Instant.EPOCH)
  val registrationRetryCount    = preference[Int]    ("push_registration_retry_count", 0)

  private val pushEnabled = uiPreferenceBooleanSignal(gcmEnabledKey).signal
  private val currentTokenPref = preference[Option[String]]("push_token", None)

  val onTokenRefresh = EventStream[String]()

  onTokenRefresh { t => setNewToken(Some(t)) }

  /**
    * Current GCM state, true if we are receiving notifications on it.
    * We are only comparing timestamps of conversation events, considering events received on GCM and fetched by EventsClient.
    * Events are fetched only when web socket connects (meaning GCM was considered active), this means that this state should rarely change.
    *
    * GcmState will be active if the last received event is up-to-date or newer than the last fetched event, OR we have registered our GCM token since the
    * last time we fetched a message (meaning we 'just registered')
    */
  private val pushState = for {
    lastFetched    <- lastFetchedConvEventTime.signal
    lastReceived   <- lastReceivedConvEventTime.signal
    localFetchTime <- lastFetchedLocalTime.signal
    lastRegistered <- lastRegistrationTime.signal
  } yield {
    verbose(s"gcmState, lastFetched: $lastFetched, lastReceived: $lastReceived, localFetchTime: $localFetchTime, lastRegistered: $lastRegistered")
    PushState(lastFetched <= lastReceived, localFetchTime <= lastRegistered)
  }

  pushState {
    case PushState(true, _) => registrationRetryCount := 0
    case _ =>
  }

  private val shouldReRegister = for {
    play    <- googleApi.isGooglePlayServicesAvailable
    state   <- pushState
    time    <- lastRegistrationTime.signal
    retries <- registrationRetryCount.signal
  } yield {
    verbose(s"should re-register, play available: $play, state: $state, lastTry: $time, retries: $retries")
    play && !state.active && RegistrationRetryBackoff.delay(math.max(0, retries - retryFailLimit)).elapsedSince(time)
  }

  shouldReRegister {
    case true =>
      (for {
        retries <- registrationRetryCount()
        if retries > retryFailLimit
        _ <- setNewToken()
      } yield retries).flatMap(retries => registrationRetryCount := retries + 1)

    case false =>
      verbose(s"shouldReRegister == false")
  }

  val currentPushToken = for {
    push  <- pushEnabled
    play  <- googleApi.isGooglePlayServicesAvailable
    st    <- pushState
    ct    <- registrationRetryCount.signal
    token <- currentTokenPref.signal
  } yield {
    //If we missed a gcm notification (say because the GCM server connection was down), then the state will be !active
    //but we don't want to turn on the websocket just yet - we would then rely on web-socket and not get any updates to GCM, meaning
    //it would be permanently disabled.
    if (push && play && (st.active || ct < retryFailLimit)) token else None
  }

  val eventProcessingStage = EventScheduler.Stage[GcmTokenRemoveEvent] { (convId, events) =>
    currentTokenPref().flatMap {
      case Some(t) if events.exists(_.token == t) =>
        verbose("Clearing all push tokens in response to backend notification")
        googleApi.deleteAllPushTokens()
        (currentTokenPref := None).flatMap(_ => setNewToken())
      case _ => Future.successful({})
    }
  }

  private def setNewToken(token: Option[String] = None): Future[Unit] = try {
    val t = token.orElse(Some(googleApi.getPushToken))
    t.foreach { t =>
      Localytics.setPushDisabled(false)
      Localytics.setPushRegistrationId(t)
    }
    verbose(s"Setting new push token: $t")
    currentTokenPref := t
  } catch {
    case NonFatal(ex) => Future.successful {
      HockeyApp.saveException(ex, s"unable to set push token")
    }
  }
}

object PushTokenService {

  /**
    * To prevent over-aggressive re-registering of GCM tokens. The connection to the Google GCM servers can be down for up to 28
    * minutes before the system realises it needs to re-establish the connection. If we miss a message in this time, and the user
    * opens the app, we'll incorrectly diagnose this as a bad token and try to re-register it. So we'll give it a few chances.
    */
  val retryFailLimit = 2

  import scala.concurrent.duration._

  val RegistrationRetryBackoff = new ExponentialBackoff(5.minutes, 30.days)

  case class PushState(received: Boolean, justRegistered: Boolean) {
    def active = received || justRegistered
  }

  case class PushSenderId(str: String) extends AnyVal
}
