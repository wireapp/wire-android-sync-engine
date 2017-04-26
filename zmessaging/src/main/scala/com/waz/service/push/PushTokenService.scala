package com.waz.service.push

import com.waz.ZLog.verbose
import com.waz.content.KeyValueStorage
import com.waz.model.ConversationEvent
import com.waz.service.PreferenceService
import com.waz.service.push.GcmGlobalService.PushSenderId
import com.waz.service.push.GcmService.GcmState
import com.waz.sync.client.EventsClient
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.wrappers.GoogleApi
import org.threeten.bp.Instant
import com.waz.utils._

import scala.concurrent.Future

class PushTokenService(googleApi:     GoogleApi,
                       senderId:      PushSenderId,
                       prefs:         PreferenceService,
                       eventsClient:  EventsClient) {

  import PushTokenService._

  implicit val dispatcher = new SerialDispatchQueue(name = "PushTokenDispatchQueue")

  private implicit val ev = EventContext.Global

  val lastReceivedConvEventTime = prefs.preference[Instant]("last_received_conv_event_time", Instant.EPOCH)
  val lastFetchedConvEventTime  = prefs.preference[Instant]("last_fetched_conv_event_time",  Instant.ofEpochMilli(1))
  val lastFetchedLocalTime      = prefs.preference[Instant]("last_fetched_local_time",       Instant.EPOCH)
  val lastRegistrationTime      = prefs.preference[Instant]("push_registration_time",        Instant.EPOCH)
  val registrationRetryCount    = prefs.preference[Int]    ("push_registration_retry_count", 0)

  /**
    * Current GCM state, true if we are receiving notifications on it.
    * We are only comparing timestamps of conversation events, considering events received on GCM and fetched by EventsClient.
    * Events are fetched only when web socket connects (meaning GCM was considered active), this means that this state should rarely change.
    *
    * GcmState will be active if the last received event is up-to-date or newer than the last fetched event, OR we have registered our GCM token since the
    * last time we fetched a message (meaning we 'just registered')
    */
  val pushState = for {
    lastFetched    <- lastFetchedConvEventTime.signal
    lastReceived   <- lastReceivedConvEventTime.signal
    localFetchTime <- lastFetchedLocalTime.signal
    lastRegistered <- lastRegistrationTime.signal
  } yield {
    verbose(s"gcmState, lastFetched: $lastFetched, lastReceived: $lastReceived, localFetchTime: $localFetchTime, lastRegistered: $lastRegistered")
    PushState(lastFetched <= lastReceived, localFetchTime <= lastRegistered)
  }

  val pushActive = googleApi.isGooglePlayServicesAvailable.flatMap {
    case true =>
      for {
        st <- pushState
        ct <- registrationRetryCount.signal
      } yield {
        //If we missed a gcm notification (say because the GCM server connection was down), then the state will be !active
        //but we don't want to turn on the websocket just yet - we would then rely on web-socket and not get any updates to GCM, meaning
        //it would be permanently disabled.
        st.active || ct < retryFailLimit
      }
    case false => Signal.const(false)
  }

  eventsClient.onNotificationsPageLoaded.map(_.notifications.flatMap(_.events).collect { case ce: ConversationEvent => ce })
    .filter(_.nonEmpty).on(dispatcher) { ces =>
    Future.traverse(ces.groupBy(_.convId)) { case (convId, evs) =>
      convsContent.convByRemoteId(convId) collect {
        case Some(conv) if !conv.muted => evs.maxBy(_.time)
      }
    } foreach { evs =>
      if (evs.nonEmpty) {
        val last = evs.maxBy(_.time).time.instant
        if (last != Instant.EPOCH) {
          lastFetchedConvEventTime := last
          lastFetchedLocalTime := Instant.now
        }
      }
    }
  }

  private def updateFetchedTimes(ces: Vector[ConversationEvent]) = {

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

}
