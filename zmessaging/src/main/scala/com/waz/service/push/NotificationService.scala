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
import com.waz.ZLog
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.NotificationsHandler.NotificationType._
import com.waz.content._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.LastRead
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.ZMessaging.{accountTag, clock}
import com.waz.service._
import com.waz.service.conversation.ConversationsListStateService
import com.waz.service.push.NotificationService.NotificationInfo
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{AggregatingSignal, EventStream, Signal}
import com.waz.utils.{RichInstant, _}
import com.waz.zms.NotificationsAndroidService
import com.waz.zms.NotificationsAndroidService.{checkNotificationsIntent, checkNotificationsTimeout}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.duration._

class GlobalNotificationsService {

  import ZLog.ImplicitTag.implicitLogTag
  import com.waz.threading.Threading.Implicits.Background

  //To be set by the UI
  val notificationsSourceVisible = Signal(Map[AccountId, Set[ConvId]]())

  lazy val groupedNotifications: Signal[Map[AccountId, (Boolean, Seq[NotificationInfo])]] = //Boolean = shouldBeSilent
    Option(ZMessaging.currentAccounts) match {
      case Some(accountsService) =>
        accountsService.zmsInstances.flatMap { zs =>
          verbose(s"zmsInstances count: ${zs.size}")
          val seq = zs.map { z =>
            val service = z.notifications
            val notifications = service.notifications
            val shouldBeSilent = service.otherDeviceActiveTime.map { t =>
              val timeDiff = clock.instant.toEpochMilli - t.toEpochMilli
              verbose(s"otherDeviceActiveTime: $t, current time: ${clock.instant}, timeDiff: ${timeDiff.millis.toSeconds}")(service.logTag)
              timeDiff < NotificationsAndroidService.checkNotificationsTimeout.toMillis
            }

            for {
              silent <- shouldBeSilent.orElse(Signal.const(false))
              nots <- notifications
            } yield {
              verbose(s"groupedNotifications for account: ${z.accountId} -> (silent: $silent, notsCount: ${nots.size})")
              z.accountId -> (silent, nots)
            }
          }.toSeq

          Signal.sequence(seq: _*).map(_.toMap)
        }
      case None =>
        error("No AccountsService available")
        Signal.empty
    }

  def markAsDisplayed(accountId: AccountId, nots: Seq[NotId]): Future[Any] = {
    Option(ZMessaging.currentAccounts) match {
      case Some(accountsService) =>
        accountsService.getZMessaging(accountId).flatMap {
          case Some(zms) => zms.notifications.markAsDisplayed(nots)
          case None      => Future.successful({})
        }
      case None =>
        error("No AccountsService available")
        Future.successful({})
    }
  }
}

class NotificationService(context:         Context,
                          accountId:       AccountId,
                          selfUserId:      UserId,
                          messages:        MessagesStorage,
                          lifeCycle:       UiLifeCycle,
                          storage:         NotificationStorage,
                          usersStorage:    UsersStorage,
                          convs:           ConversationStorage,
                          members:         MembersStorage,
                          reactionStorage: ReactionsStorage,
                          userPrefs:       UserPreferences,
                          pushService:     PushService,
                          convsStats:      ConversationsListStateService,
                          globalNots:      GlobalNotificationsService) {

  import NotificationService._
  import com.waz.utils.events.EventContext.Implicits.global

  private implicit val dispatcher = new SerialDispatchQueue(name = "NotificationService")
  implicit lazy val logTag: LogTag = accountTag[NotificationService](accountId)

  val alarmService = Option(context) match {
    case Some(c) => Some(c.getSystemService(Context.ALARM_SERVICE).asInstanceOf[AlarmManager])
    case _ =>
      warn("No context, could not start alarm service")
      None
  }

  //For UI to decide if it should make sounds or not
  val otherDeviceActiveTime = Signal(Instant.EPOCH)

  private var lastNots = Set.empty[NotId] // prevents from creating the same notifications twice
  val notifications = storage.notifications.filter(_.keys.toSet != lastNots).flatMap { nots =>
    lastNots = nots.keys.toSet
    val d = nots.values.toIndexedSeq.sorted
    Signal.future(createNotifications(d))
  }

  val lastReadProcessingStage = EventScheduler.Stage[GenericMessageEvent] { (convId, events) =>
    events.foreach {
      case GenericMessageEvent(_, _, _, GenericMessage(_, LastRead(conv, time))) =>
        otherDeviceActiveTime ! clock.instant
        alarmService.foreach(_.set(AlarmManager.RTC, (clock.instant + checkNotificationsTimeout).toEpochMilli, checkNotificationsIntent(accountId, context)))
      case _ =>
    }
    Future.successful(())
  }

  globalNots.notificationsSourceVisible { sources =>
    sources.get(accountId).foreach { convs =>
      removeNotifications(nd => convs.contains(nd.conv))
    }
  }

  def markAsDisplayed(ns: Seq[NotId]) = storage.updateAll2(ns, n => n.copy(hasBeenDisplayed = true))

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
  })

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

    def loadAll() = convs.getAllConvs.map(_.map(c => c.id -> convLastRead(c)).toMap)

    def update(times: Map[ConvId, Instant], updates: Seq[ConversationData]) =
      times ++ updates.map(c => c.id -> convLastRead(c))(breakOut)

    new AggregatingSignal(timeUpdates, loadAll(), update)
  }

  lastReadMap.throttle(ClearThrottling) { lrMap =>
    removeNotifications { n =>
      val lastRead = lrMap.getOrElse(n.conv, Instant.EPOCH)
      val removeIf = !lastRead.isBefore(n.time)
      verbose(s"Removing notif(${n.id}) if lastRead: $lastRead is not before n.time: ${n.time}?: $removeIf")
      removeIf
    }
  }

  messages.onAdded { msgs => add(msgs.flatMap(notification)) }

  messages.onUpdated { updates =>
    // add notification when message sending fails
    val failedMsgs = updates collect {
      case (prev, msg) if prev.state != msg.state && msg.state == Message.Status.FAILED => msg
    }
    if (failedMsgs.nonEmpty) {
      storage.insertAll(failedMsgs map { msg => NotificationData(NotId(msg.id), msg.contentString, msg.convId, msg.userId, MESSAGE_SENDING_FAILED) })
    }

    // add notifications for uploaded assets
    val updatedAssets = updates collect {
      case (prev, msg) if msg.state == Message.Status.SENT && msg.msgType == Message.Type.ANY_ASSET => msg
    }
    if (updatedAssets.nonEmpty) add(updatedAssets.flatMap(notification))
  }

  messages.onDeleted { ids =>
    storage.removeAll(ids.map(NotId(_)))
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

      storage.removeAll(toRemove.map(NotId(_))).flatMap { _ =>
        add(toAdd.valuesIterator.map(r => NotificationData(NotId(r.id), "", convsByMsg.getOrElse(r.message, ConvId(r.user.str)), r.user, LIKE, time = r.timestamp, referencedMessage = Some(r.message))).toVector)
      }
    }.logFailure(reportHockey = true)
  }

  reactionStorage.onDeleted { ids =>
    storage.removeAll(ids.map(NotId(_)))
  }

  def removeNotifications(filter: NotificationData => Boolean = (_: NotificationData) => true) = {
    storage.notifications.head flatMap { data =>
      val toRemove = data collect {
        case (id, n) if filter(n) => id
      }
      storage.removeAll(toRemove)
    }
  }

  private def add(notifications: Seq[NotificationData]) =
    for {
      lrMap <- lastReadMap.head
      notificationSourceVisible <- globalNots.notificationsSourceVisible.head
      res <- storage.insertAll(notifications filter { n =>
        //Filter notifications for those coming from other users, and that have come after the last-read time for their respective conversations.
        //Note that for muted conversations, the last-read time is set to Instant.MAX, so they can never come after.
        val lastRead = lrMap.get(n.conv)
        val sourceVisible = notificationSourceVisible.get(accountId).exists(_.contains(n.conv))
        val filter = n.user != selfUserId && lastRead.forall(_.isBefore(n.time)) && !sourceVisible
        verbose(s"Inserting notif(${n.id}) if conv lastRead: $lastRead isBefore ${n.time}?: $filter")

        filter
      })
      _ = if (res.nonEmpty) verbose(s"inserted: ${res.size} notifications")
    } yield res

  private def createNotifications(ns: Seq[NotificationData]): Future[Seq[NotificationInfo]] = {
    verbose(s"createNotifications: ${ns.size}")
    Future.traverse(ns) { data =>
      verbose(s"processing notif: $data")
      usersStorage.get(data.user).flatMap { user =>
        val userName = user map (_.getDisplayName) filterNot (_.isEmpty) orElse data.userName
        val userPicture = user.flatMap(_.picture)

        data.msgType match {
          case CONNECT_REQUEST | CONNECT_ACCEPTED =>
            Future.successful(NotificationInfo(data.id, data.msgType, data.time, data.msg, data.conv, convName = userName,
              userName = userName, userPicture = userPicture, isEphemeral = data.ephemeral, hasBeenDisplayed = data.hasBeenDisplayed))
          case _ =>
            for {
              msg  <- data.referencedMessage.fold2(Future.successful(None), messages.getMessage)
              conv <- convs.get(data.conv) if conv.isDefined
              membersCount <- members.getByConv(conv.get.id).map(_.map(_.userId).size)
            } yield {
              val (notificationData, maybeLikedContent) =
                if (data.msgType == LIKE) (data.copy(msg = msg.fold("")(_.contentString)), msg.map { m =>
                  m.msgType match {
                    case Message.Type.ASSET => LikedContent.PICTURE
                    case Message.Type.TEXT | Message.Type.TEXT_EMOJI_ONLY => LikedContent.TEXT_OR_URL
                    case _ => LikedContent.OTHER
                  }
                })
                else (data, None)

              val groupConv = if (!conv.exists(_.team.isDefined)) conv.exists(_.convType == ConversationType.Group)
              else membersCount > 2

              NotificationInfo(
                notificationData.id,
                notificationData.msgType,
                notificationData.time,
                notificationData.msg,
                notificationData.conv,
                convName = conv.map(_.displayName),
                userName = userName,
                userPicture = userPicture,
                isEphemeral = data.ephemeral,
                isGroupConv = groupConv,
                isUserMentioned = data.mentions.contains(selfUserId),
                likedContent = maybeLikedContent,
                hasBeenDisplayed = data.hasBeenDisplayed)
            }
        }
      }
    }
  }
}

object NotificationService {

  //var for tests
  var ClearThrottling = 3.seconds

  case class NotificationInfo(id: NotId,
                              tpe: NotificationType,
                              time: Instant,
                              message: String,
                              convId: ConvId,
                              convName: Option[String] = None,
                              userName: Option[String] = None,
                              userPicture: Option[AssetId] = None,
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
