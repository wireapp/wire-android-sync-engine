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
package com.waz.content

import com.waz.RobolectricUtils
import com.waz.api.User.ConnectionStatus
import com.waz.model.UserData.UserDataDao
import com.waz.model.{UserData, UserId}
import com.waz.testutils.Matchers._
import com.waz.threading.Threading
import com.waz.utils.events.EventContext.Implicits.global
import com.waz.utils.events.EventStream
import com.waz.utils.wrappers.DB
import org.robolectric.Robolectric
import org.scalatest._

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

@Ignore class UsersStorageSpec extends FeatureSpec with Matchers with BeforeAndAfter with RobolectricTests with RobolectricUtils { test =>
  lazy val testUsers = 1 to 100 map { x => UserData(UserId(x.toString), s"user-$x") }
  lazy val newUser = UserData(UserId("new"), "new-user")
  lazy val defaultUser = UserData(UserId("default"), "default")

  val updater = (_: UserData).copy(connection = ConnectionStatus.ACCEPTED)

  val notified: mutable.Map[UserId, Boolean] = mutable.Map().withDefaultValue(false)
  val added: mutable.Map[UserId, Boolean] = mutable.Map().withDefaultValue(false)

  var users: UsersStorageImpl = _
  var storage: ZmsDatabase = _

  before {
    storage = new ZmsDatabase(UserId(), Robolectric.application)
    implicit def db: DB = storage.dbHelper.getWritableDatabase

    UserDataDao.deleteAll
    UserDataDao.insertOrReplace(testUsers)

    users = new UsersStorageImpl(Robolectric.application, storage)

    notified.clear()
    added.clear()
  }

  feature("Retrieving users") {
    scenario("Retrieve an existing, non-cached or cached user") {
      attemptTwice { users.get(testUsers(0).id) should eventually(be(Some(testUsers(0)))) }
    }

    scenario("Retrieve a user that doesn't exist (uncached and cached)") {
      attemptTwice { users.get(UserId()) should eventually(be(None)) }
    }

    scenario("multiple users") {
      val someUsers = testUsers.take(10)
      attemptTwice { users.listAll(someUsers map (_.id)) should eventually(be(someUsers)) }
    }

    scenario("multiple users where some don't exist") {
      val someUsers = testUsers.take(10) :+ UserData(UserId("invalid"), "user-invalid")
      users.listAll(someUsers map (_.id)) should eventually(be(testUsers.take(10)))
    }

    scenario("get or create non-existing") {
      observeUserUri(newUser.id)
      users.getOrElseUpdate(newUser.id, newUser) should eventually(be(newUser))
      notified(newUser.id) shouldEqual true
      users.get(newUser.id) should eventually(equalIgnoreDisplay(Some(newUser)))
    }

    scenario("get or create existing uncached") {
      val existing = testUsers(0)
      users.getOrElseUpdate(existing.id, newUser) should eventually(be(existing))
      users.get(newUser.id) should eventually(be(None))
    }

    scenario("get or create existing cached") {
      val existing = testUsers(0)
      users.get(existing.id) should eventually(be(Some(existing)))
      users.getOrElseUpdate(existing.id, newUser) should eventually(be(existing))
      users.get(newUser.id) should eventually(be(None))
    }
  }

  feature("adding users") {
    scenario("add non-existing user") {
      observeUserUri(newUser.id)
      users.addOrOverwrite(newUser) should eventually(be(newUser))
      notified(newUser.id) shouldEqual true
      users.get(newUser.id) should eventually(equalIgnoreDisplay(Some(newUser)))
    }
  }

  feature("updating single users") {
    scenario("update a non-existing user") {
      observeUserUri(newUser.id)
      users.update(newUser.id, updater) should eventually(be(None))
      notified(newUser.id) shouldEqual false
      users.get(newUser.id) should eventually(equalIgnoreDisplay(None))
    }

    scenario("updating an existing, uncached user") {
      val updated = updater(testUsers(0))
      observeUserUri(testUsers(0).id)
      users.update(testUsers(0).id, updater) should eventually(be(Some((testUsers(0), updated))))
      notified(testUsers(0).id) shouldEqual true
      users.get(testUsers(0).id) should eventually(equalIgnoreDisplay(Some(updated)))
    }

    scenario("updating an existing, cached user") {
      users.get(testUsers(0).id) should eventually(be(Some(testUsers(0))))
      val updated = updater(testUsers(0))
      observeUserUri(testUsers(0).id)
      users.update(testUsers(0).id, updater) should eventually(be(Some((testUsers(0), updated))))
      notified(testUsers(0).id) shouldEqual true
      users.get(testUsers(0).id) should eventually(equalIgnoreDisplay(Some(updated)))
    }

    scenario("update or create a non-existing user") {
      observeUserUri(newUser.id)
      observeAdded(newUser.id)
      users.updateOrCreate(newUser.id, updater, newUser) should eventually(be(newUser))
      notified(newUser.id) shouldEqual true
      added(newUser.id) shouldEqual true
      users.get(newUser.id) should eventually(equalIgnoreDisplay(Some(newUser)))
    }

    scenario("update or create an existing, uncached user") {
      val updated = updater(testUsers(0))
      Seq(testUsers(0).id, defaultUser.id) foreach { id =>
        observeUserUri(id)
        observeAdded(id)
      }
      users.updateOrCreate(testUsers(0).id, updater, defaultUser) should eventually(be(updated))
      notified(testUsers(0).id) shouldEqual true
      added(testUsers(0).id) shouldEqual false
      notified(defaultUser.id) shouldEqual false
      added(defaultUser.id) shouldEqual false
      users.get(testUsers(0).id) should eventually(equalIgnoreDisplay(Some(updated)))
    }

    scenario("update or create an existing, cached user") {
      users.get(testUsers(0).id) should eventually(be(Some(testUsers(0))))
      val updated = updater(testUsers(0))
      Seq(testUsers(0).id, defaultUser.id) foreach { id =>
        observeUserUri(id)
        observeAdded(id)
      }
      users.updateOrCreate(testUsers(0).id, updater, defaultUser) should eventually(be(updated))
      notified(testUsers(0).id) shouldEqual true
      added(testUsers(0).id) shouldEqual false
      notified(defaultUser.id) shouldEqual false
      added(defaultUser.id) shouldEqual false
      users.get(testUsers(0).id) should eventually(equalIgnoreDisplay(Some(updated)))
    }
  }

  feature("updating multiple users") {
    scenario("update or create multiple users") {
        val toUpdate = testUsers.take(10)
        val toCreate = Seq(newUser)
        val all: Map[UserId, Option[UserData] => UserData] = (toUpdate ++ toCreate) .map { v =>
          v.id -> { u: Option[UserData] => u map updater getOrElse newUser }
        } .toMap
        val updated = ((toUpdate map updater) ++ toCreate).sortBy(_.name)
        Seq(testUsers(0).id, newUser.id) foreach { id =>
          observeUserUri(id)
          observeAdded(id)
        }

        users.updateOrCreateAll(all) .map (_.toSeq.sortBy(_.name))(Threading.Background) should eventually(be(updated))

        added(testUsers(0).id) shouldEqual false
        notified(testUsers(0).id) shouldEqual true
        added(newUser.id) shouldEqual true
        notified(newUser.id) shouldEqual true
        users.get(newUser.id) should eventually(equalIgnoreDisplay(Some(newUser)))
        users.get(testUsers(1).id) should eventually(equalIgnoreDisplay(Some(updater(testUsers(1)))))
      }
  }

  feature("user signal") {
    scenario("load user with signal") {
      val signal = users.signal(testUsers.head.id)
      Await.result(EventStream.wrap(signal).next, 1.second) shouldEqual testUsers.head
    }

    scenario("fire signal when user is modified") {

      @volatile var user = Option.empty[UserData]
      val signal = users.signal(testUsers.head.id)
      signal { u => user = Some(u) }

      withDelay { user shouldEqual testUsers.headOption }

      users.update(testUsers.head.id, _.copy(name = "changed name"))

      withDelay { user.map(_.name) shouldEqual Some("changed name") }
    }
  }

  feature("contact name changes")(pending)

  feature("saving to the database")(pending)

  feature("cleaning the cache")(pending)

  private def equalIgnoreDisplay(u: Option[UserData]) = be(u.map(_.copy(displayName = ""))).compose { (u: Option[UserData]) => u.map(_.copy(displayName = "")) }
  private def equalIgnoreDisplay(u: UserData) = be(u.copy(displayName = "")).compose { (u: UserData) => u.copy(displayName = "") }

  private def attemptTwice(op: => Unit): Unit = 1 to 2 foreach (i => withClue(s"attempt $i)")(op))

  private def observeUserUri(id: UserId): Unit = {
    notified(id) = false
    users.onAdded { users => if (users.exists(_.id == id)) notified(id) = true }
    users.onUpdated { us => if (us.exists(_._2.id == id)) notified(id) = true }
  }

  private def observeAdded(id: UserId): Unit = {
    added(id) = false
    users.onAdded { users => if (users.exists(_.id == id)) added(id) = true }
  }
}
