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
package com.waz.service.push

import android.app.AlarmManager
import android.content.Context
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.NotificationsHandler.NotificationType._
import com.waz.content.UserPreferences.LastUiVisibleTime
import com.waz.content._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.LastRead
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service._
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events.{AggregatingSignal, EventStream, Signal}
import com.waz.zms.NotificationsAndroidService.{checkNotificationsIntent, checkNotificationsTimeout}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future

class NotificationService(context: Context, accountId: AccountId, selfUserId: UserId, messages: MessagesStorageImpl, lifeCycle: ZmsLifeCycle,
                          storage: NotificationStorage, usersStorage: UsersStorageImpl, convs: ConversationStorageImpl, reactionStorage: ReactionsStorage,
                          userPrefs: UserPreferences, timeouts: Timeouts, pushService: PushServiceImpl) {

  import NotificationService._
  import com.waz.utils.events.EventContext.Implicits.global

  private implicit val dispatcher = new SerialDispatchQueue(name = "NotificationService")

  @volatile private var uiActive = false

  private val lastUiVisibleTime = userPrefs.preference(LastUiVisibleTime)

  val alarmService = context.getSystemService(Context.ALARM_SERVICE).asInstanceOf[AlarmManager]

  //For UI to decide if it should make sounds or not
  val otherDeviceActiveTime = Signal(Instant.EPOCH)

  val notifications = lifeCycle.accInForeground(accountId).zip(for {
    data <- storage.notifications.map(_.values.toIndexedSeq.sorted)
    uiVisibleTime <- lastUiVisibleTime.signal
  } yield {
    verbose(s"Retrieved from notifications storage: ${fCol(data)}, lastUiVisibleTime: $uiVisibleTime")
    if (data.forall(_.time.isBefore(uiVisibleTime))) Seq.empty[NotificationData] // no new messages, don't show anything
    else data
  }).flatMap {
    case (true, _) => Signal.const(Seq.empty[NotificationInfo])
    case (_, data) => Signal.future(createNotifications(data))
  }

  val lastReadProcessingStage = EventScheduler.Stage[GenericMessageEvent] { (convId, events) =>
    events.foreach {
      case GenericMessageEvent(_, _, _, GenericMessage(_, LastRead(conv, time))) =>
        otherDeviceActiveTime ! Instant.now
        alarmService.set(AlarmManager.RTC, Instant.now().toEpochMilli + checkNotificationsTimeout.toMillis, checkNotificationsIntent(context))
      case _ =>
    }
    Future.successful(())
  }

  def markAsDisplayed(ns: Seq[NotId]) = storage.updateAll2(ns, n => n.copy(hasBeenDisplayed = true))

  (for {
    inForeground <- lifeCycle.accInForeground(accountId)
    drift <- pushService.beDrift //TODO BE do NOT want us to rely on this time, we should find a better way, but for now, it's better than using local time
  } yield (inForeground, drift)) { case (inForeground, drift) =>
    uiActive = returning(inForeground) { inForeGround =>
      if (inForeGround || uiActive) {
        val inst = Instant.now()
        verbose(s"Account last active at $inst with BE drift: $drift")
        lastUiVisibleTime := inst + drift
      }
    }
  }

  val notificationEventsStage = EventScheduler.Stage[Event]({ (c, events) =>
    add(events collect {
      case ev @ UserConnectionEvent(_, _, userId, msg, ConnectionStatus.PendingFromOther, time, name) if ev.hasLocalTime =>
        NotificationData(NotId(CONNECT_REQUEST, userId), msg.getOrElse(""), ConvId(userId.str), userId, CONNECT_REQUEST, time.instant, userName = name)
      case ev @ UserConnectionEvent(_, _, userId, _, ConnectionStatus.Accepted, time, name) if ev.hasLocalTime =>
        NotificationData(NotId(CONNECT_ACCEPTED, userId), "", ConvId(userId.str), userId, CONNECT_ACCEPTED, userName = name)
      case ContactJoinEvent(userId, _) =>
        verbose("ContactJoinEvent")
        NotificationData(NotId(CONTACT_JOIN, userId), "", ConvId(userId.str), userId, CONTACT_JOIN)
    })
  }, _ => ! uiActive)

  /**
    * Map containing lastRead time for every conversation.
    * For muted conversations lastRead is always set to Instant.MAX,
    * we don't want to show notifications for muted conversations.
    */
  private val lastReadMap = {
    def convLastRead(c: ConversationData) = if (c.muted) Instant.MAX else c.lastRead

    val timeUpdates = EventStream.union(
      convs.onAdded,
      convs.onUpdated map { _ collect { case (prev, conv) if convLastRead(prev) != convLastRead(conv) => conv } }
    ) filter(_.nonEmpty)

    def loadAll() = convs.getAll.map(_.map(c => c.id -> convLastRead(c)).toMap)

    def update(times: Map[ConvId, Instant], updates: Seq[ConversationData]) =
      times ++ updates.map(c => c.id -> convLastRead(c))(breakOut)

    new AggregatingSignal(timeUpdates, loadAll(), update)
  }

  // remove notifications once UI is active
  lastUiVisibleTime.signal.throttle(timeouts.notifications.clearThrottling)(removeNotificationsAfterUiActive)

  // remove notifications read by other devices
  lastReadMap.throttle(timeouts.notifications.clearThrottling) { lrMap =>
    removeNotifications { n =>
      val lastRead = lrMap.get(n.conv)
      val filter = lastRead.exists(!_.isBefore(n.time))
      verbose(s"Removing not(${n.id}) if exists $lastRead that !isBefore ${n.time}?: $filter")
      filter
    }
  }

  messages.onAdded { msgs => add(msgs.flatMap(notification)) }

  messages.onUpdated { updates =>
    // add notification when message sending fails
    val failedMsgs = updates collect {
      case (prev, msg) if prev.state != msg.state && msg.state == Message.Status.FAILED => msg
    }
    if (!uiActive && failedMsgs.nonEmpty) {
      storage.insert(failedMsgs map { msg => NotificationData(NotId(msg.id), msg.contentString, msg.convId, msg.userId, MESSAGE_SENDING_FAILED) })
    }

    // add notifications for uploaded assets
    val updatedAssets = updates collect {
      case (prev, msg) if msg.state == Message.Status.SENT && msg.msgType == Message.Type.ANY_ASSET => msg
    }
    if (updatedAssets.nonEmpty) add(updatedAssets.flatMap(notification))
  }

  messages.onDeleted { ids =>
    storage.remove(ids.map(NotId(_)))
  }

  reactionStorage.onChanged { reactions =>
    val reactionsFromOthers = reactions.filterNot(_.user == selfUserId)

    messages.getAll(reactionsFromOthers.map(_.message)).flatMap { msgs =>
      val convsByMsg = msgs.iterator.flatten.by[MessageId, Map](_.id).mapValues(_.convId)
      val myMsgs = msgs.collect { case Some(m) if m.userId == selfUserId => m.id }(breakOut): Set[MessageId]
      val rs = reactionsFromOthers.filter(r => myMsgs contains r.message).sortBy(_.timestamp)
      val (toRemove, toAdd) = rs.foldLeft((Set.empty[(MessageId, UserId)], Map.empty[(MessageId, UserId), Liking])) {
        case ((rs, as), r @ Liking(m, u, t, Liking.Action.Like))  => (rs - r.id, as + (r.id -> r))
        case ((rs, as), r @ Liking(m, u, t, Liking.Action.Unlike)) => (rs + r.id, as - r.id)
      }

      storage.remove(toRemove.map(NotId(_))).flatMap { _ =>
        if (! uiActive)
          add(toAdd.valuesIterator.map(r => NotificationData(NotId(r.id), "", convsByMsg.getOrElse(r.message, ConvId(r.user.str)), r.user, LIKE, referencedMessage = Some(r.message))).toVector)
        else
          Future.successful(Nil)
      }
    }.logFailure(reportHockey = true)
  }

  reactionStorage.onDeleted { ids =>
    storage.remove(ids.map(NotId(_)))
  }

  def clearNotifications() = {
    lastUiVisibleTime := Instant.now()
    // will execute removeNotifications as part of this call,
    // this ensures that it's actually done while wakeLock is acquired by caller
    removeNotificationsAfterUiActive(Instant.now)
  }

  private def removeNotificationsAfterUiActive(uiLastVisible: Instant) = {
    //will effectively remove all
    removeNotifications { n =>
      verbose(s"Removing n(${n.id}) if uiLastVisible: $uiLastVisible is after ${n.time}")
      uiLastVisible.isAfter(n.time)
    }
  }

  private def removeNotifications(filter: NotificationData => Boolean): Unit = {
    storage.notifications.head flatMap { data =>
      val toRemove = data collect {
        case (id, n) if filter(n) => id
      }
      storage.remove(toRemove)
    }
  }

  private def add(notifications: Seq[NotificationData]) =
    for {
      lrMap <- lastReadMap.head
      res <- storage.insert(notifications filter { n =>
        //Filter notifications for those coming from other users, and that have come after the last-read time for their respective conversations.
        //Note that for muted conversations, the last-read time is set to Instant.MAX, so they can never come after.
        val lastRead = lrMap.get(n.conv)
        val filter = n.user != selfUserId && lastRead.forall(_.isBefore(n.time))
        verbose(s"Filtering for not(${n.id}) if forall lastRead: $lastRead it isBefore ${n.time}?: $filter")
        filter
      })
      _ = if (res.nonEmpty) verbose(s"inserted: ${res.size} notifications")
    } yield res

  private def createNotifications(ns: Seq[NotificationData]): Future[Seq[NotificationInfo]] = {

    Future.traverse(ns) { data =>
      usersStorage.get(data.user).flatMap { user =>
        val userName = user map (_.getDisplayName) filterNot (_.isEmpty) orElse data.userName

        data.msgType match {
          case CONNECT_REQUEST | CONNECT_ACCEPTED =>
            Future.successful(NotificationInfo(data.id, data.msgType, data.time, data.msg, data.conv, convName = userName, userName = userName, isEphemeral = data.ephemeral, isGroupConv = false, hasBeenDisplayed = data.hasBeenDisplayed))
          case _ =>
            for {
              msg <- data.referencedMessage.fold2(Future.successful(None), messages.getMessage)
              conv <- convs.get(data.conv)
            } yield {
              val (g, t) =
                if (data.msgType == LIKE) (data.copy(msg = msg.fold("")(_.contentString)), msg.map(m => if (m.msgType == Message.Type.ASSET) LikedContent.PICTURE else LikedContent.TEXT_OR_URL))
                else (data, None)
              NotificationInfo(g.id, g.msgType, g.time, g.msg, g.conv, convName = conv.map(_.displayName), userName = userName, isEphemeral = data.ephemeral, isGroupConv = conv.exists(_.convType == ConversationType.Group), isUserMentioned = data.mentions.contains(selfUserId), likedContent = t, hasBeenDisplayed = data.hasBeenDisplayed)
            }
        }
      }
    }
  }
}

object NotificationService {

  case class NotificationInfo(id: NotId,
                              tpe: NotificationType,
                              time: Instant,
                              message: String,
                              convId: ConvId,
                              convName: Option[String] = None,
                              userName: Option[String] = None,
                              isGroupConv: Boolean = false,
                              isUserMentioned: Boolean = false,
                              isEphemeral: Boolean = false,
                              likedContent: Option[LikedContent] = None,
                              hasBeenDisplayed: Boolean = false
  )

  def mapMessageType(mTpe: Message.Type, protos: Seq[GenericMessage], members: Set[UserId], sender: UserId): Option[NotificationType] = {
    import Message.Type._
    mTpe match {
      case TEXT | TEXT_EMOJI_ONLY | RICH_MEDIA => Some(NotificationType.TEXT)
      case KNOCK        => Some(NotificationType.KNOCK)
      case ASSET        => Some(NotificationType.ASSET)
      case LOCATION     => Some(NotificationType.LOCATION)
      case RENAME       => Some(NotificationType.RENAME)
      case MISSED_CALL  => Some(NotificationType.MISSED_CALL)
      case ANY_ASSET    => Some(NotificationType.ANY_ASSET)
      case AUDIO_ASSET  => Some(NotificationType.AUDIO_ASSET)
      case VIDEO_ASSET  => Some(NotificationType.VIDEO_ASSET)
      case MEMBER_JOIN  =>
        if (members == Set(sender)) None // ignoring auto-generated member join event when user accepts connection
        else Some(NotificationType.MEMBER_JOIN)
      case MEMBER_LEAVE => Some(NotificationType.MEMBER_LEAVE)
      case _ => None
    }
  }

  def notification(msg: MessageData): Option[NotificationData] = {
    mapMessageType(msg.msgType, msg.protos, msg.members, msg.userId).map {
      tp => NotificationData(NotId(msg.id), if (msg.isEphemeral) "" else msg.contentString, msg.convId, msg.userId, tp, if (msg.time == Instant.EPOCH) msg.localTime else msg.time, ephemeral = msg.isEphemeral, mentions = msg.mentions.keys.toSeq)
    }
  }
}
