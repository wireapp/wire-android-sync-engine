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
package com.waz.cache

import com.waz.RobolectricUtils
import com.waz.ZLog.LogTag
import com.waz.cache.CachedStorageSpec.{Item, ItemDao}
import com.waz.content.ZmsDatabase
import com.waz.db.Dao
import com.waz.model.UserId
import com.waz.testutils.Matchers._
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.wrappers.{DB, DBCursor}
import com.waz.utils.{CachedStorageImpl, RichFuture, TrimmingLruCache}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables
import org.scalatest.time.{Millis, Seconds, Span}

import scala.collection.breakOut
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

@Ignore class CachedStorageSpec extends FeatureSpec with Tables with Matchers with BeforeAndAfter with RobolectricTests with RobolectricUtils with ScalaFutures { test =>
  implicit val defaultPatience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  import com.waz.utils.events.EventContext.Implicits.global

  implicit lazy val dispatcher = new SerialDispatchQueue(name = "db cache")
  implicit val timout: Timeout = 5.seconds
  implicit val tag: LogTag = "DbCacheSpec"

  lazy val db = new ZmsDatabase(UserId(), context)

  val testItems = Seq(Item("meep", "meepValue"), Item("mewp", "mewpValue"), Item("foo", "fooValue"), Item("bar", "barValue"), Item("count", "0"))
  val testData = testItems .map { i => i.k -> i } .toMap

  val data = new scala.collection.mutable.HashMap[String, Item]

  var delay = 200.millis
  val halfDelay = delay / 2
  val delayAndHalf = delay * 3 / 2
  val miniDelay = delay / 5

  var storage: CachedStorageImpl[String, Item] = _
  var created: Seq[String] = Nil
  var updated: Seq[(String, String)] = Nil
  var removed: Seq[String] = Nil
  var singleLoaderInvoked = false

  before {
    data.clear()
    data ++= testData
    delay = 200.millis

    storage = new CachedStorageImpl[String, Item](new TrimmingLruCache[String, Option[Item]](context, Fixed(6)), db)(ItemDao, "CachedStorage") {

      override protected def delete(keys: Iterable[String])(implicit db: DB): Unit = {
        Thread.sleep(delay.toMillis)
        data --= keys
      }

      override protected def load(key: String)(implicit db: DB): Option[Item] = {
        Thread.sleep(delay.toMillis)
        singleLoaderInvoked = true
        data.get(key)
      }

      override protected def load(keys: Set[String])(implicit db: DB): Vector[Item] = {
        Thread.sleep(delay.toMillis)
        keys.flatMap(data.get)(breakOut)
      }

      override protected def save(values: Seq[Item])(implicit db: DB): Unit = {
        Thread.sleep(delay.toMillis)
        data ++= values map { i => i.k -> i }
      }
    }

    created = Nil
    updated = Nil
    removed = Nil
    singleLoaderInvoked = false

    storage.onAdded { is => created ++= is.map(_.v) }
    storage.onUpdated { is => updated ++= is.map { case (p, u) => p.v -> u.v } }
    storage.onDeleted { removed ++= _ }
  }

  feature("Getting values from the cache") {
    scenario("Get values via loader correctly.") {
      testItems foreach { item =>
        storage.get(item.k) should eventually(be(Some(item)))(delay * 10)
      }
    }

    scenario("Get values that do not exist.") {
      storage.get("does not exist") should eventually(be(None))
    }

    scenario("Get an existing value via loader, and then from the cache.") {
      forAll(Table(("attempt", "timeout"), (1, delayAndHalf), (2, halfDelay))) { (nr: Int, t: FiniteDuration) =>
        storage.get("meep") should eventually(be(Some(Item("meep", "meepValue"))))(t)
      }
    }

    scenario("Get a non-existing value via loader, and then from the cache.") {
      forAll(Table(("attempt", "timeout"), (1, delayAndHalf), (2, halfDelay))) { (nr: Int, t: FiniteDuration) =>
        storage.get("invalid") should eventually(be(None))(t)
      }
    }

    scenario("Get multiple values via loader.") {
      forAll(Table(("attempt", "timeout"), (1, delay * 6 + halfDelay), (2, delayAndHalf))) { (nr: Int, t: FiniteDuration) =>
        storage.getAll(testData.keys.toSeq :+ "invalid") should eventually(be(testData.values.toSeq.map(Some(_)) :+ None))(t)
      }
    }

    scenario("Get multiple values via the batch loader.") {
      forAll(Table(("attempt", "timeout"), (1, delay * 6 + halfDelay), (2, delayAndHalf))) { (nr: Int, t: FiniteDuration) =>
        storage.getAll(testData.keys.toSeq :+ "invalid") should eventually(be(testData.values.toSeq.map(Some(_)) :+ None))(t)
        singleLoaderInvoked shouldBe false
        storage.get(testData.keys.head) should eventually(be(testData.get(testData.keys.head)))(t)
        singleLoaderInvoked shouldBe false
        storage.get("invalid") should eventually(be(None))(t)
        singleLoaderInvoked shouldBe false
      }
    }
  }

  feature("Putting values into the cache directly") {
    scenario("Put a new value into the cache") {
      storage.put("new", Item("new", "newValue")).futureValue
      storage.get("new") should eventually(be(Some(Item("new", "newValue"))))(halfDelay)
    }

    scenario("Put an existing value into the cache") {
      storage.get("meep") should eventually(be(Some(Item("meep", "meepValue"))))(delayAndHalf)
      storage.put("meep", Item("meep", "newMeep")).futureValue
      storage.get("meep") should eventually(be(Some(Item("meep", "newMeep"))))(halfDelay)
    }
  }

  feature("Removing values from the cache") {
    scenario("Removing an existing value") {
      storage.get("meep") should eventually(be(Some(Item("meep", "meepValue"))))(delayAndHalf)
      storage.remove("meep").futureValue
      storage.get("meep") should eventually(be(None))(halfDelay)
      removed shouldEqual Seq("meep")
    }

    scenario("Removing a non-existing value") {
      storage.remove("lalala").futureValue
      storage.get("lalala") should eventually(be(None))(halfDelay)
      removed shouldEqual Seq("lalala")
    }
  }

  feature("Getting values or creating them") {
    scenario("Get or create retrieves an existing value") {
      forAll(Table(("attempt", "timeout"), (1, delayAndHalf), (2, halfDelay))) { (nr: Int, t: FiniteDuration) =>
        storage.getOrCreate("meep", Item("meep", "lalala")) should eventually(be(Item("meep", "meepValue")))(t)
      }
      created shouldEqual Nil
      storage.get("meep") should eventually(be(Some(Item("meep", "meepValue"))))(halfDelay)
    }

    scenario("Get or create creates a completely new value") {
      storage.getOrCreate("new", Item("new", "newValue")) should eventually(be(Item("new", "newValue")))(delay * 5 / 2)
      created shouldEqual Seq("newValue")
      created = Nil
      storage.getOrCreate("new", Item("new", "newValue")) should eventually(be(Item("new", "newValue")))(halfDelay)
      created shouldEqual Nil
    }

    scenario("Get or create creates a new value for a removed value") {
      storage.remove("meep").futureValue
      removed shouldEqual Seq("meep")
      storage.getOrCreate("meep", Item("meep", "lalala")) should eventually(be(Item("meep", "lalala")))(delayAndHalf)
      created shouldEqual Seq("lalala")
      storage.get("meep") should eventually(be(Some(Item("meep", "lalala"))))(halfDelay)
    }

    scenario("Get or create with concurrent put, random but consistent") {
      (0 to 10) foreach { i =>
        created = Nil
        updated = Nil
        val key = s"new_$i"
        RichFuture.zip(storage.getOrCreate(key, Item(key, "newValue")), storage.put(key, Item(key, "putValue"))).futureValue match {
          case (Item(`key`, "putValue"), Item(`key`, "putValue")) =>
            data.get(key) shouldEqual Some(Item(key, "putValue"))
            created shouldEqual Seq("putValue")
            updated shouldEqual Nil
          case (Item(`key`, "newValue"), Item(`key`, "putValue")) =>
            data.get(key) shouldEqual Some(Item(key, "putValue"))
            created shouldEqual Seq("newValue")
            updated shouldEqual Seq(("newValue", "putValue"))
          case res => fail(s"unexpected result: $res")
        }
      }
    }
  }

  feature("Updating values") {
    scenario("Update an existing non-loaded value") {
      storage.update("meep", _ + "New").futureValue shouldEqual Some(Item("meep", "meepValue") -> Item("meep", "meepValueNew"))
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "meepValueNew"))
      updated shouldEqual Seq(("meepValue", "meepValueNew"))
    }

    scenario("Update an existing loaded value") {
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "meepValue"))
      storage.update("meep", _ + "New").futureValue shouldEqual Some(Item("meep", "meepValue") -> Item("meep", "meepValueNew"))
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "meepValueNew"))
      updated shouldEqual Seq(("meepValue", "meepValueNew"))
    }

    scenario("Update a non-existing value") {
      storage.update("invalid", _ + "New") should eventually(be(None))(delayAndHalf)
      storage.get("invalid") should eventually(be(None))(halfDelay)
      updated shouldEqual Nil
    }

    scenario("Concurrent update and put can give random, but consistent results") {
      (0 to 10) foreach { i =>
        created = Nil
        updated = Nil
        val key = s"new_$i"
        RichFuture.zip(storage.update(key, _ + "Value"), storage.put(key, Item(key, "putValue"))).futureValue match {
          case (None, Item(`key`, "putValue")) =>
            data.get(key) shouldEqual Some(Item(key, "putValue"))
            created shouldEqual Seq("putValue")
            updated shouldEqual Nil
          case (Some((Item(`key`, "putValue"), Item(`key`, "putValueValue"))), Item(`key`, "putValue")) =>
            data.get(key) shouldEqual Some(Item(key, "putValueValue"))
            created shouldEqual Seq("putValue")
            updated shouldEqual Seq(("putValue", "putValueValue"))
          case res => fail(s"unexpected result: $res")
        }
      }
    }
  }

  feature("Update or create values") {
    scenario("Update or create a non-existent value") {
      storage.updateOrCreate("new", _ + "Value", Item("new", "initialValue")).futureValue shouldEqual Item("new", "initialValue")
      updated shouldEqual Nil
      created shouldEqual Seq("initialValue")
      storage.get("new").futureValue shouldEqual Some(Item("new", "initialValue"))
    }

    scenario("Update or create an existing non-loaded value") {
      storage.updateOrCreate("meep", _ + "New", Item("meep", "initialValue")).futureValue shouldEqual Item("meep", "meepValueNew")
      updated shouldEqual Seq(("meepValue", "meepValueNew"))
      created shouldEqual Nil
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "meepValueNew"))
    }

    scenario("Update or create an existing loaded value") {
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "meepValue"))
      storage.updateOrCreate("meep", _ + "New", Item("meep", "initialValue")).futureValue shouldEqual Item("meep", "meepValueNew")
      updated shouldEqual Seq(("meepValue", "meepValueNew"))
      created shouldEqual Nil
      storage.get("meep") should eventually(be(Some(Item("meep", "meepValueNew"))))(halfDelay)
    }

    scenario("Updating or creating an existing value without changing it") {
      storage.updateOrCreate("meep", identity, Item("meep", "initialValue")).futureValue shouldEqual Item("meep", "meepValue")
      updated shouldEqual Nil
      created shouldEqual Nil
      storage.get("meep") should eventually(be(Some(Item("meep", "meepValue"))))(halfDelay)
    }

    scenario("Updating or creating and put concurrently") {
      (0 to 10) foreach { i =>
        created = Nil
        updated = Nil
        val key = s"new_$i"
        RichFuture.zip(storage.updateOrCreate(key, _ + "New", Item(key, "initialValue")), storage.put(key, Item(key, "putValue"))).futureValue match {
          case (Item(`key`, "initialValue"), Item(`key`, "putValue")) => // put is executed second
            data.get(key) shouldEqual Some(Item(key, "putValue"))
            created shouldEqual Seq("initialValue")
            updated shouldEqual Seq(("initialValue", "putValue"))
          case (Item(`key`, "putValueNew"), Item(`key`, "putValue")) => // pu is executed first
            data.get(key) shouldEqual Some(Item(key, "putValueNew"))
            created shouldEqual Seq("putValue")
            updated shouldEqual Seq(("putValue", "putValueNew"))
          case res => fail(s"unexpected result: $res")
        }
      }
    }

    scenario("Updating or creating and remove") {
      val f = storage.updateOrCreate("meep", _ + "New", Item("meep", "initialValue"))
      storage.remove("meep").futureValue
      f.futureValue shouldEqual Item("meep", "initialValue")
      created shouldEqual Seq("initialValue")
      updated shouldEqual Nil
      removed shouldEqual Seq("meep")
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "initialValue"))
    }

    scenario("Update from multiple threads") {
      delay = 10.millis
      val f = Future.traverse((0 until 100).toVector) { _ =>
        storage.update("count", _ + 1)
      }
      Await.result(f, 10.seconds)
      storage.get("count").futureValue shouldEqual Some(Item("count", "100"))
      data.get("count") shouldEqual Some(Item("count", "100"))
    }

    scenario("Batch update from multiple threads") {
      delay = 10.millis
      storage.put("count", Item("count", "0")).futureValue

      val f = Future.traverse((0 until 100).toVector) { _ =>
        storage.updateOrCreateAll(Map("count" -> { _.fold(Item("count", "0"))(_ + 1) }))
      }
      Await.result(f, 10.seconds)
      storage.get("count").futureValue shouldEqual Some(Item("count", "100"))
      data.get("count") shouldEqual Some(Item("count", "100"))
    }
  }


  feature("Update or create all") {
    lazy val updaters = (testData.keys.toSeq.sorted :+ "new").map(k => k -> updater(k)).toMap

    scenario("Update some values from the loader, some from the cache and some non-existing ones") {
      storage.get("meep").futureValue shouldEqual Some(Item("meep", "meepValue"))
      storage.updateOrCreateAll(updaters).futureValue should contain theSameElementsAs (testData.values.map(_ + "New").toSet + Item("new", "initialValue-new"))
      created.toSet shouldEqual Set("initialValue-new")
      updated.toSet shouldEqual testData.values.map(i => (i.v, i.v + "New")).toSet
      storage.get("new") should eventually(be(Some(Item("new", "initialValue-new"))))(halfDelay)
      storage.get("meep") should eventually(be(Some(Item("meep", "meepValueNew"))))(halfDelay)
      storage.get("foo") should eventually(be(Some(Item("foo", "fooValueNew"))))(halfDelay)
    }
  }

  feature("Cache overflow") {
    scenario("adding lots of entries will clean out the previous entries") {
      val updaters = (1 to 10) .map (_.toString) .map { k => k -> updater(k) } .toMap
      storage.updateOrCreateAll(updaters).futureValue should not be empty
      storage.getRawCached("meep") shouldEqual null
      val f = storage.get("meep")
      awaitUi(halfDelay)
      f.value shouldEqual None
      f.futureValue shouldEqual Some(Item("meep", "meepValue"))
    }
  }

  def updater(key: String): Option[Item] => Item = _.fold(Item(key, s"initialValue-$key"))(_ + "New")
}

object CachedStorageSpec {

  case class Item(k: String, v: String) {
    def +(str: String) = Item(k, v + str)
    def +(i: Int) = Item(k, (v.toInt + i).toString)
  }

  implicit object ItemDao extends Dao[Item, String] {
    import com.waz.db.Col._
    import com.waz.db._
    val Key = text('key, "PRIMARY KEY").apply(_.k)
    val Value = text('value).apply(_.v)

    override val idCol: ItemDao.Column[String] = Key
    override val table: Table[Item] = new Table("Items", Key, Value)

    override def apply(implicit c: DBCursor): Item = Item(Key, Value)
  }
}
