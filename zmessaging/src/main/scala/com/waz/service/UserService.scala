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
package com.waz.service

import java.util.Date

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.AccentColor
import com.waz.content.UserPreferences.LastSlowSyncTimeKey
import com.waz.content._
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.UserService._
import com.waz.service.assets.AssetService
import com.waz.service.push.PushService
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.UserSearchClient.UserSearchEntry
import com.waz.sync.client.UsersClient
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.events.{AggregatingSignal, EventContext, Signal}

import scala.collection.breakOut
import scala.concurrent.{Awaitable, Future}

trait UserService {
  def selfUserId: UserId
  def updateOrCreateUser(id: UserId, update: UserData => UserData, create: => UserData): Future[UserData]
  def getOrCreateUser(id: UserId): Future[UserData]
  def updateUserData(id: UserId, updater: UserData => UserData): Future[Option[(UserData, UserData)]]
  def withSelfUserFuture[A](f: UserId => Future[A]): Future[A]
  def updateConnectionStatus(id: UserId, status: UserData.ConnectionStatus, time: Option[Date] = None, message: Option[String] = None): Future[Option[UserData]]
  def getUsers(ids: Seq[UserId]): Future[Seq[UserData]]
  def getUser(id: UserId): Future[Option[UserData]]
  def syncIfNeeded(users: UserData*): Future[Unit]
  def updateUsers(entries: Seq[UserSearchEntry]): Future[Set[UserData]]
  def acceptedOrBlockedUsers: Signal[Map[UserId, UserData]]
  def updateAvailability(availability: Availability): Future[Option[UserData]]

  def processAvailability(availability: Map[UserId, Availability]): Future[Any]
}

class UserServiceImpl(override val selfUserId: UserId,
                      account:        AccountId,
                      accounts:       AccountsService,
                      usersStorage:   UsersStorage,
                      userPrefs:      UserPreferences,
                      push:           PushService,
                      assets:         AssetService,
                      usersClient:    UsersClient,
                      sync:           SyncServiceHandle,
                      assetsStorage:  AssetsStorage) extends UserService {

  import Threading.Implicits.Background
  private implicit val ec = EventContext.Global
  import userPrefs._

  val selfUser: Signal[UserData] = usersStorage.optSignal(selfUserId) flatMap {
    case Some(data) => Signal.const(data)
    case None =>
      sync.syncSelfUser()
      Signal.empty
  }

  lazy val lastSlowSyncTimestamp = preference(LastSlowSyncTimeKey)
  val userUpdateEventsStage = EventScheduler.Stage[UserUpdateEvent]((c, e) => for {
      _ <- updateSyncedUsers(e.filterNot(_.removeIdentity).map(_.user)(breakOut))
      _ <- removeIdentityFromSyncedUsers(e.filter(_.removeIdentity).map(_.user)(breakOut))
    } yield {}
  )
  val userDeleteEventsStage = EventScheduler.Stage[UserDeleteEvent]((c, e) => updateUserDeleted(e.map(_.user)(breakOut)))

  //Update user data for other accounts
  //TODO remove this and move the necessary user data up to the account storage
  accounts.loggedInAccounts.map(_.flatMap(_.userId).toSeq.filterNot(_ == selfUserId))(syncNotExistingOrExpired)

  push.onHistoryLost { time =>
    verbose(s"onSlowSyncNeeded, updating timestamp to: $time")
    lastSlowSyncTimestamp := Some(time.toEpochMilli)
  }

  override lazy val acceptedOrBlockedUsers: Signal[Map[UserId, UserData]] =
    new AggregatingSignal[Seq[UserData], Map[UserId, UserData]](
      usersStorage.onChanged, usersStorage.listUsersByConnectionStatus(acceptedOrBlocked),
      { (accu, us) =>
        val (toAdd, toRemove) = us.partition(u => acceptedOrBlocked(u.connection))
        accu -- toRemove.map(_.id) ++ toAdd.map(u => u.id -> u)
      }
    )

  private lazy val acceptedOrBlocked = Set(ConnectionStatus.Accepted, ConnectionStatus.Blocked)

  def withSelfUser[A](f: UserId => CancellableFuture[A]) = f(selfUserId)

  def withSelfUserFuture[A](f: UserId => Future[A]) = f(selfUserId)

  def selfUserOrFail: Future[UserId] = withSelfUserFuture(Future(_))

  def getOrCreateUser(id: UserId) = usersStorage.getOrElseUpdate(id, {
    sync.syncUsers(id)
    UserData(id, None, defaultUserName, None, None, connection = ConnectionStatus.Unconnected, searchKey = SearchKey(defaultUserName), handle = None)
  })

  def getSelfUserId: Future[Option[UserId]] = Future successful Some(selfUserId)

  def updateOrCreateUser(id: UserId, update: UserData => UserData, create: => UserData) =
    usersStorage.updateOrCreate(id, update, create)

  def updateUserConversation(id: UserId, convId: RConvId) = usersStorage.update(id, _.copy(conversation = Some(convId)))

  def updateConnectionStatus(id: UserId, status: UserData.ConnectionStatus, time: Option[Date] = None, message: Option[String] = None): Future[Option[UserData]] =
    usersStorage.update(id, { user => returning(user.updateConnectionStatus(status, time, message))(u => verbose(s"updateConnectionStatus($u)")) }) map {
      case Some((prev, updated)) if prev != updated => Some(updated)
      case _ => None
    }

  def updateAvailability(availability: Availability): Future[Option[UserData]] = {
    verbose(s"updateAvailability($availability)")
    updateSelfAndSync(_.copy(availability = availability), _ => sync.postAvailability(availability))
  }

  def updateUserData(id: UserId, updater: UserData => UserData) = usersStorage.update(id, updater)

  override def updateUsers(entries: Seq[UserSearchEntry]): Future[Set[UserData]] = {
    def updateOrAdd(entry: UserSearchEntry) = (_: Option[UserData]).fold(UserData(entry))(_.updated(entry))
    usersStorage.updateOrCreateAll(entries.map(entry => entry.id -> updateOrAdd(entry)).toMap)
  }

  override def getUser(id: UserId): Future[Option[UserData]] = {
    debug(s"getUser($id)")

    usersStorage.get(id) map {
      case Some(data) =>
        syncIfNeeded(data)
        Some(data)
      case _ =>
        sync.syncUsers(id)
        None
    }
  }

  def userSignal(id: UserId): Signal[UserData] =
    usersStorage.optSignal(id) flatMap {
      case None =>
        sync.syncUsers(id)
        Signal.empty[UserData]
      case Some(data) =>
        syncIfNeeded(data)
        Signal const data
    }

  def syncSelfNow: Future[Option[UserData]] = Serialized.future("syncSelfNow", selfUserId) {
    usersClient.loadSelf().future.flatMap {
      case Right(info) =>
        //TODO Dean - remove after v2 transition time
        val v2profilePic = info.mediumPicture.filter(_.convId.isDefined)

        v2profilePic.fold(Future.successful(())){ pic =>
          verbose("User has v2 picture - re-uploading as v3")
          for {
            _ <- sync.postSelfPicture(v2profilePic.map(_.id))
            _ <- assetsStorage.updateAsset(pic.id, _.copy(convId = None)) //mark assets as v3
            _ <- usersClient.updateSelf(info).future
          } yield (())
        }.flatMap (_ => updateSyncedUsers(Seq(info)) map { _.headOption })
      case Left(err) =>
        error(s"loadSelf() failed: $err")
        Future.successful(None)
    }
  }

  def deleteAccount(): Future[SyncId] = sync.deleteAccount()

  def withSelfUserId[T <: Awaitable[Option[UserData]]](f: UserId => T) = f(selfUserId)

  def getSelfUser: Future[Option[UserData]] =
    usersStorage.get(selfUserId) flatMap {
      case Some(userData) => Future successful Some(userData)
      case _ => syncSelfNow
    }

  def updateAndSync(userId: UserId, updater: UserData => UserData, sync: UserData => Future[_]) =
    updateUserData(userId, updater) flatMap {
      case Some((p, u)) if p != u => sync(u) map (_ => Some(u))
      case _ => Future successful None
    }

  def updateSelfAndSync(updater: UserData => UserData, sync: UserData => Future[_]) =
    updateAndSync(selfUserId, updater, sync)

  // called from ui to update user
  def updateSelf(name: Option[String] = None, phone: Option[PhoneNumber] = None, accent: Option[AccentColor] = None, handle: Option[Handle] = None): Future[Option[UserData]] =
    updateSelfAndSync(_.updated(name, None, phone, accent, handle = handle), data => sync.postSelfUser(UserInfo(data.id, name, accent.map(_.id), phone = phone, handle = handle)))

  def clearSelfPicture(): Future[Option[UserData]] =
    updateSelfAndSync(_.copy(picture = None), _ => sync.postSelfPicture(None))

  def updateSelfPicture(image: com.waz.api.ImageAsset): Future[Option[UserData]] =
    assets.addImageAsset(image, RConvId(selfUserId.str), isSelf = true) flatMap { asset =>
      updateAndSync(selfUserId, _.copy(picture = Some(asset.id)), _ => sync.postSelfPicture(Some(asset.id)))
    }

  def getUsers(ids: Seq[UserId]): Future[Seq[UserData]] =
    usersStorage.listAll(ids) map { users =>
      syncIfNeeded(users: _*)
      users
    }

  /**
   * Schedules user data sync if user with given id doesn't exist or has old timestamp.
   */
  def syncNotExistingOrExpired(users: Seq[UserId]): Future[Unit] = usersStorage.listAll(users) flatMap { found =>
    val toSync = (users.toSet -- found.map(_.id)).toSeq
    if (toSync.nonEmpty) sync.syncUsers(toSync: _*) flatMap (_ => syncIfNeeded(found: _*)) else syncIfNeeded(found: _*)
  }

  /**
    * Schedules user data sync if stored user timestamp is older than last slow sync timestamp.
   */
  override def syncIfNeeded(users: UserData*): Future[Unit] =
    lastSlowSyncTimestamp() flatMap {
      //TODO: Remove empty picture check when not needed anymore
      case Some(time) => sync.syncUsersIfNotEmpty(users.filter(user => user.syncTimestamp < time || user.picture.isEmpty).map(_.id))
      case _ => sync.syncUsersIfNotEmpty(users.filter(_.picture.isEmpty).map(_.id))
    }

  def updateSyncedUsersPictures(users: UserInfo*): Future[_] = assets.updateAssets(users.flatMap(_.picture.getOrElse(Seq.empty[AssetData])))

  def updateSyncedUsers(users: Seq[UserInfo], timestamp: Long = System.currentTimeMillis()): Future[Set[UserData]] = {
    debug(s"update synced users: $users, service: $this")
    updateSyncedUsersPictures(users: _*) flatMap { _ =>
      def updateOrCreate(info: UserInfo): Option[UserData] => UserData = {
        case Some(user: UserData) => user.updated(info).copy(syncTimestamp = timestamp, connection = if (selfUserId == info.id) ConnectionStatus.Self else user.connection)
        case None => UserData(info).copy(syncTimestamp = timestamp, connection = if (selfUserId == info.id) ConnectionStatus.Self else ConnectionStatus.Unconnected)
      }
      usersStorage.updateOrCreateAll(users.map { info => info.id -> updateOrCreate(info) }(breakOut))
    }
  }

  def removeIdentityFromSyncedUsers(users: Seq[UserInfo], timestamp: Long = System.currentTimeMillis()): Future[Unit] = {
    def update(current: UserData): UserData = {
      val userInfo = users.find(_.id == current.id)
      userInfo.fold(current){ info =>
        current.copy(
          email = if (info.email.nonEmpty) None else current.email,
          phone = if (info.phone.nonEmpty) None else current.phone)
      }
    }
    usersStorage.updateAll2(users.map {user => user.id}, update).map(_ => ())
  }

  def updateUserDeleted(userIds: Vector[UserId]): Future[Any] =
    usersStorage.updateAll2(userIds, _.copy(deleted = true))

  def processAvailability(avMap: Map[UserId, Availability]): Future[Any] = {
    def update(user: UserData): UserData = avMap.get(user.id).fold(user){ av => user.copy(availability = av) }

    usersStorage.updateAll2(avMap.keySet, update)
  }
}

object UserService {
  val defaultUserName: String = ""
}
