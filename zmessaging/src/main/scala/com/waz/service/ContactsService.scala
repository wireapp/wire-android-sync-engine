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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils.queryNumEntries
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.LOLLIPOP
import android.provider.ContactsContract.DisplayNameSources._
import android.provider.{BaseColumns, ContactsContract}
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.waz.PermissionsService
import com.waz.ZLog._
import com.waz.api.Permission.READ_CONTACTS
import com.waz.content._
import com.waz.model.AddressBook.ContactHashes
import com.waz.model.Contact.{ContactsDao, ContactsOnWireDao, EmailAddressesDao, PhoneNumbersDao}
import com.waz.model.ConversationData.{ConversationDataDao, ConversationType}
import com.waz.model._
import com.waz.sync.SyncServiceHandle
import com.waz.threading.Threading
import com.waz.utils.Locales.{currentLocaleOrdering, sortWithCurrentLocale}
import com.waz.utils._
import com.waz.utils.events._
import com.waz.zms.R
import org.threeten.bp.Instant
import org.threeten.bp.Instant.now

import scala.collection.mutable.ArrayBuffer
import scala.collection.{GenMap, GenSet, breakOut, mutable => mut}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.util.{Success, Try}

class ContactsService(context: Context, accountId: AccountId, accountStorage: AccountsStorageImpl, lifecycle: ZmsLifecycle,
                      keyValue: KeyValueStorage, prefs: PreferenceServiceImpl, users: UserService, usersStorage: UsersStorage,
                      timeouts: Timeouts, phoneNumbers: PhoneNumberService, storage: ZmsDatabase, sync: SyncServiceHandle,
                      convs: ConversationStorage, permissions: PermissionsService) {

  import ContactsService._
  import EventContext.Implicits.global
  import Threading.Implicits.Background
  import keyValue._
  import lifecycle._
  import timeouts.contacts._

  private[service] val initFuture = init()
  private def init() = permissions.request(Set(READ_CONTACTS), delayUntilProviderIsSet = true).flatMap(_ =>
    Future.sequence(Seq(requestUploadIfNeeded(), updateContactsAndMatchesOnStart())))

  lifecycle.loggedIn.onChanged {
    case true => if (initFuture.isCompleted) init()
    case false =>
  }(EventContext.Global)

  shareContactsPref.signal.onChanged {
    case true =>
      verbose(s"contact sharing allowed")
      markContactsDirty()
      requestUploadIfNeeded()
      updateContactsAndMatches()
    case false =>
      verbose(s"contact sharing not allowed")
      markContactsDirty()
      updateContactsAndMatches()
      storage(AddressBook.save(AddressBook.Empty)(_))
  }(lifecycle)

  lifecycleState.on(Background) {
    case LifecycleState.UiActive => initFuture.flatMap(_ => requestUploadIfNeeded())
    case state =>
  }(lifecycle)

  contactsObserver.onChanged.on(Background) { _ =>
    verbose("contacts provider signaled change; marking contacts list for reload")
    markContactsDirty()
  }(EventContext.Global)

  storage.read(ContactsOnWireDao.list(_)) foreach (cs => contactsOnWire.mutate(_ ++ cs))

  usersStorage.onAdded { us =>
    // TODO: batching
    us foreach { a =>
      updatedContactMatches(a) foreach { contacts =>
        if (contacts.nonEmpty) contactsOnWire.mutate(_.addToAfterset(a.id, contacts))
      }
    }
  }

  usersStorage.onDeleted { ids =>
    contactsOnWire.mutate { cs => ids.foldLeft(cs)(_ removeLeft _) }
    storage { db =>
      ids foreach { id => ContactsOnWireDao.delete(ContactsOnWireDao.User, id)(db) }
    }
  }

  usersStorage.onUpdated { updates =>
    // TODO: batching
    updates foreach {
      case (a, b) =>
        if (a.phone != b.phone || a.email != b.email) updatedContactMatches(b) foreach { contacts =>
          if (contacts.nonEmpty) contactsOnWire.mutate(_.addToAfterset(b.id, contacts))
          else contactsOnWire.mutate(_.removeLeft(b.id))
        }
    }
  }

  private def updatedContactMatches(user: UserData): Future[Set[ContactId]] = storage { implicit db =>
    val start = nanoNow
    returning(user.phone.fold2(Set.empty[ContactId], p => PhoneNumbersDao.findBy(p)) ++ user.email.fold2(Set.empty, e => EmailAddressesDao.findBy(e))) { contacts =>
      if (user.hasEmailOrPhone) ContactsOnWireDao.delete(ContactsOnWireDao.User, user.id)
      if (contacts.nonEmpty) ContactsOnWireDao.insertOrIgnore(contacts.iterator.map((user.id, _)))
      verbose(s"user ${user.id} locally matches ${contacts.size} contact(s) [${start.untilNow}]")
    }
  }

  private[waz] lazy val lastUploadTime = keyValuePref[Option[Instant]]("address_book_last_upload_time", None)
  private[service] lazy val addressBookVersionOfLastUpload = keyValuePref[Option[Int]]("address_book_version_of_last_upload", None)
  private lazy val shareContactsPrefKey = Try(context.getString(R.string.pref_share_contacts_key)).getOrElse(PrefKey) // fallback for testing
  private[service] lazy val shareContactsPref = prefs.preference[Boolean](shareContactsPrefKey, defaultValue = true, prefs.uiPreferences)
  private lazy val contactsObserver = new ContentObserverSignal(Contacts)(context)
  private lazy val contactsNeedReloading = new AtomicBoolean(true)

  private def markContactsDirty(): Unit = {
    contactsNeedReloading.set(true)
    emailAddressesCache.clear()
    phoneNumbersCache.clear()
  }

  def unifiedContacts(): Signal[UnifiedContacts] =
    Signal(contactsSignal.map(_.filter(_._2.hasProperName)), acceptedOrPendingUsers, contactsOnWireSignal, contactsUpdater) flatMap { case (contacts, acceptedOrPending, (onWire, onWireUsers), _) =>
      Signal.future(Future {
        val start = nanoNow
        val users: Map[UserId, UserData] = acceptedOrPending ++ onWireUsers
        val notOnWire = contacts.keysIterator.filterNot(onWire.containsRight).to[ArrayBuffer]
        val onWireButUnconnected = onWire.aftersets.keysIterator.filterNot(uid => acceptedOrPending.contains(uid) || users.get(uid).forall(_.isConnected)).to[ArrayBuffer]

        val allIds = notOnWire.iterator.map(Right[UserId, ContactId](_)).++(acceptedOrPending.keysIterator.++(onWireButUnconnected.iterator).map(Left[UserId, ContactId](_))).to[ArrayBuffer]
        def sortKey(id: Either[UserId, ContactId]): String = id.fold(u => users(u).getDisplayName, c => contacts(c).sortKey)
        val sortedIds = sortWithCurrentLocale(allIds, sortKey)

        val indexing = Locales.indexing()
        def initial(idx: Int): String = indexing.labelFor(sortedIds(idx).fold(u => users(u).getDisplayName, c => contacts(c).sortKey))
        val groupedByInitial: SeqMap[String, IndexedSeq[Int]] = {
          val grouped = sortedIds.indices.groupBy(initial)
          val other = grouped.get("#")
          val alpha = (grouped - "#").toVector.sortBy(_._1)(currentLocaleOrdering)
          SeqMap(other.fold2(alpha, o => alpha :+ ("#", o)))(_._1, _._2)
        }

        verbose(s"unified contacts in ${start.untilNow}: ${acceptedOrPending.size} accepted/pending user(s), ${contacts.size} total contact(s) (${notOnWire.size} not on Wire), ${onWire.size} user(s) match a contact (${onWireButUnconnected.size} not connected)")

        val top10Contacts = onWire.aftersets.valuesIterator.flatMap(_.headOption).toSet.take(10).toVector
        val totalCount = onWire.aftersets.size
        UnifiedContacts(contacts, users, sortedIds, groupedByInitial, TopContactsOnWire(top10Contacts, totalCount))
      })
    }

  private def contactsUpdater: Signal[Unit] = new Signal[Unit](Some(())) {
    override protected def onWire(): Unit = updateContactsAndMatches() // should happen when contacts are opened initially and whenever app comes to foreground
  }

  lazy val acceptedOrPendingUsers: Signal[Map[UserId, UserData]] = new AggregatingSignal[Seq[UserData], Map[UserId, UserData]](usersStorage.onChanged, usersStorage.listAcceptedOrPendingUsers, { (accu, us) =>
    val (toAdd, toRemove) = us.partition(_.isAcceptedOrPending)
    accu -- toRemove.map(_.id) ++ toAdd.map(u => u.id -> u)
  })

  lazy val contactsOnWire = Signal(BiRelation.empty[UserId, ContactId])

  private def contactsOnWireSignal = contactsOnWire.throttle(userMatchingInterval).flatMap { br =>
    new AggregatingSignal[Seq[UserData], Map[UserId, UserData]](
      usersStorage.onChanged.map(_.filter(u => br.containsLeft(u.id))).filter(_.nonEmpty),
      usersStorage.listAll(br.aftersets.keys).map(_.by[UserId, Map](_.id)),
      (prev, us) => prev ++ us.map(u => u.id -> u)
    ).map(us => (br, us))
  }

  private lazy val contactsSignal: Signal[GenMap[ContactId, Contact]] = new AggregatingSignal[IndexedSeq[Contact], IndexedSeq[Contact]](contactsLoaded, initialContactsLoading, (stale, fresh) => fresh).map(_.by[ContactId, mut.HashMap](_.id))

  def contactForUser(id: UserId) = contactsOnWire.map(_.aftersets).map(_.get(id).flatMap(_.headOption)).zip(contactsSignal).map {
    case (Some(cId), contacts) => contacts.get(cId)
    case _ => None
  }

  private def initialContactsLoading: Future[IndexedSeq[Contact]] =
    storage.read(db => logTime(s"loading first $InitialContactsBatchSize contacts")(ContactsDao.list(ContactsDao.listCursorWithLimit(Some(InitialContactsBatchSize))(db)))).andThen {
      case Success(loaded) =>
        if (loaded.size < InitialContactsBatchSize) () // there are no more contacts to load
        else storage.read(db => logTime(s"loading all contacts")(ContactsDao.list(db))).onSuccess { case v => contactsLoaded ! v }
    }

  lazy val contactsLoaded = EventStream[IndexedSeq[Contact]]()

  private def updateContactsAndMatchesOnStart(): Future[Unit] =
    convs.find[(ConvId, Boolean), mut.HashMap[ConvId, Boolean]](isEstablished, ConversationDataDao.establishedConversations(_), c => c.id -> c.archived).flatMap { ids =>
      if (ids.size <= 5 && ids.valuesIterator.forall(! _)) {
        verbose("user has <= 5 established conversations and no conversation is archived; will update contacts now (if needed)")
        updateContactsAndMatches()
      } else Future(verbose(s"established: ${ids.size}, archived: ${ids.valuesIterator.count(identity)}"))
    }.recoverWithLog()

  private def isEstablished(c: ConversationData) = (c.convType == ConversationType.OneToOne || c.convType == ConversationType.Group) && c.activeMember && ! c.hidden

  private def updateContactsAndMatches(): Future[Unit] =
    if (contactsNeedReloading.compareAndSet(true, false)) {
      def nonMatching(onWire: Vector[(UserId, ContactId)], users: GenMap[UserId, UserData], contacts: GenMap[ContactId, Contact]): GenSet[(UserId, ContactId)] =
        onWire.iterator.filter { case (uid, cid) => users.get(uid).forall(u => u.email.isDefined || u.phone.isDefined) } .filterNot { case (uid, cid) =>
          users.get(uid).exists { u =>
            contacts.get(cid).exists (c => u.phone.exists(c.phoneNumbers) || u.email.exists(c.emailAddresses))
          }
        }.to[mut.HashSet]

      def updateWithLimit(limit: Option[Int]): Future[Int] = {
        verbose(s"updateWithLimit: $limit")
        val start = nanoNow

        for {
          updated  <- sharedContacts(limit)
          _        <- storage(ContactsDao.save(updated)(_)).future
          onWire   <- storage.read(ContactsOnWireDao.list(_))
          contacts  = updated.by[ContactId, mut.HashMap](_.id)
          userIds   = onWire.map(_._1)(breakOut): mut.HashSet[UserId]
          users    <- usersStorage.listAll(userIds).map(_.by[UserId, mut.HashMap](_.id))
          toDelete  = nonMatching(onWire, users, contacts) // XXX as of now, we can never disassociate contacts from users whose email/phone we do not know; to do this properly, we would need a proper server-side social graph API
          _        <- Future(contactsOnWire.mutate(b => BiRelation((b.iterator ++ onWire).filterNot(toDelete))))
          _        <- storage(ContactsOnWireDao.deleteEvery(toDelete)(_)).future
          _        <- Future(contactsLoaded ! updated)
        } yield {
          verbose(s"imported ${updated.size} contact(s) (limit: $limit) in ${start.untilNow}")
          updated.size
        }
      }

      val initialLimit = InitialContactsBatchSize - 1 // smaller than load batch (in #initialContactsLoading) so it does not get loaded twice there

      storage.read(db => queryNumEntries(db, ContactsDao.table.name)).flatMap { count =>
        if (count > 0) updateWithLimit(None)
        else updateWithLimit(Some(initialLimit)).flatMap(imported => if (imported < initialLimit) Future.successful(()) else updateWithLimit(None))
      }
    }.recoverWithLog() else Future.successful(())

  def addContactsOnWire(rels: Traversable[(UserId, ContactId)]): Future[Unit] = storage(ContactsOnWireDao.insertOrIgnore(rels)(_)).future.map(_ => contactsOnWire.mutate(_ ++ rels))

  private[waz] def requestUploadIfNeeded() =
    atMostOncePer(accountId, uploadCheckInterval) {
      verbose(s"requestUploadIfNeeded()")

      def atLeastOncePerUploadMaxDelayOrOnVersionUpgrade = for {
        timeOfLastUpload <- lastUploadTime()
        lastVersion      <- addressBookVersionOfLastUpload()
      } yield (lastVersion forall (_ < CurrentAddressBookVersion)) || (timeOfLastUpload exists uploadMaxDelay.elapsedSince)

      def atMostOncePerUploadMinDelayAndOnlyIfThereAreNewHashesIn[A](current: AddressBook) = for {
        timeOfLastUpload <- lastUploadTime()                if timeOfLastUpload exists uploadMinDelay.elapsedSince
        prev             <- previouslyUploadedAddressBook() if (current - prev).nonEmpty
        _                <- sync postAddressBook current
      } yield ()

      for {
        priorityUpload  <- atLeastOncePerUploadMaxDelayOrOnVersionUpgrade
        sharingEnabled  <- shareContactsPref() if sharingEnabled || priorityUpload
        hashes          <- addressBook // will be empty & only contain self hashes if sharing is disabled
        _               <- if (priorityUpload) sync postAddressBook hashes
                           else atMostOncePerUploadMinDelayAndOnlyIfThereAreNewHashesIn(hashes)
      } yield ()
    }

  private def selfUserHashes: Future[Vector[String]] =
    for {
      phone <- phoneNumbers.myPhoneNumber
      account <- accountStorage.get(accountId)
      email = account.flatMap(_.email).flatMap(_.normalized)
      myPhone = account.flatMap(_.phone)
    } yield
      withSHA2 { digest =>
        Vector(email.map(e => digest(e.str)), myPhone.map(p => digest(p.str)), phone.filterNot(myPhone.contains).map(p => digest(p.str))).flatten
      }

  private[service] def addressBook: Future[AddressBook] = {
    val selfUser = selfUserHashes
    val phones = sharedPhoneNumbers(None)
    val emails = sharedEmailAddresses(None)
    for {
      self <- selfUser
      ps   <- phones
      es   <- emails
    } yield withSHA2 { digest =>
      val hashes = ArrayBuffer.empty[ContactHashes]
      (ps.keysIterator ++ es.keysIterator) foreach { k =>
        hashes += ContactHashes(ContactId(digest(k)), mut.HashSet.empty ++ (ps.getOrElse(k, Set.empty).iterator.map(p => digest(p.str)) ++ es.getOrElse(k, Set.empty).iterator.map(e => digest(e.str))))
      }
      AddressBook(self, hashes).withoutDuplicates
    }
  }

  private def previouslyUploadedAddressBook() = storage.read(AddressBook.load(_))

  private def sharedContacts(maybeLimit: Option[Int]): Future[IndexedSeq[Contact]] = {
    val start = nanoNow

    val phones = sharedPhoneNumbers(maybeLimit)
    val emails = sharedEmailAddresses(maybeLimit)
    def nonNull(s: String) = if (s ne null) s else ""

    for {
      phonesById <- phones
      emailsById <- emails
      ids         = mut.Set.empty[String] ++= (emailsById.keysIterator ++ phonesById.keysIterator)
      contacts   <- load(Contacts, maybeLimit.map(_ => OrderBySortKey), Visible, Col.RowId, Col.Name, Col.NameSource, Col.SortKey)(
        new Sink[IndexedSeq[Contact]] {
          val buf = ArrayBuffer.empty[Contact]
          val limit = maybeLimit.getOrElse(Int.MaxValue)

          def done: IndexedSeq[Contact] = buf

          def cont(cursor: Cursor): Boolean = {
            val idStr = cursor.getString(0)
            val name = nonNull(cursor.getString(1)).trim
            val source = cursor.getInt(2) match {
              case STRUCTURED_NAME => NameSource.StructuredName
              case NICKNAME        => NameSource.Nickname
              case _               => NameSource.Other
            }
            val sortKey = nonNull(cursor.getString(3))
            if ((idStr ne null) && (ids contains idStr)) withSHA2 { digest =>
              buf += Contact(ContactId(digest(idStr)), name, source, sortKey, SearchKey(name), phonesById.getOrElse(idStr, Set.empty[PhoneNumber]), emailsById.getOrElse(idStr, Set.empty[EmailAddress]))
            }
            buf.size < limit
          }
        })
    } yield {
      verbose(s"loaded ${contacts.size} contact(s) from provider in ${start.untilNow}")
      contacts
    }
  }

  private def sharedPhoneNumbers(maybeLimit: Option[Int]): Future[GenMap[String, GenSet[PhoneNumber]]] = {
    def loading(limit: Int) = load(Phones, maybeLimit.map(_ => OrderBySortKey), Visible, Col.ContactId, Col.EmailAddress)(new Sink[GenMap[String, GenSet[PhoneNumber]]] {
      val util = PhoneNumberUtil.getInstance()
      val buf = mut.Map.empty[String, mut.Set[PhoneNumber]]

      def done: GenMap[String, GenSet[PhoneNumber]] = buf

      def cont(cursor: Cursor): Boolean = {
        val id = cursor.getString(0)
        val phone = cursor.getString(1)
        if ((id ne null) && (phone ne null)) {
          val a = phoneNumbers.normalizeNotThreadSafe(PhoneNumber(phone), util)
          if (a.isDefined) buf.getOrElseUpdate(id, mut.HashSet.empty) += a.get
        }
        buf.size < limit
      }
    })

    maybeLimit.fold2(phoneNumbersCache cached loading(Int.MaxValue), loading)
  }

  private lazy val phoneNumbersCache = new FutureCache[GenMap[String, GenSet[PhoneNumber]]]

  private def sharedEmailAddresses(maybeLimit: Option[Int]): Future[GenMap[String, GenSet[EmailAddress]]] = {
    def loading(limit: Int) = load(Emails, maybeLimit.map(_ => OrderBySortKey), Visible, Col.ContactId, Col.EmailAddress)(new Sink[GenMap[String, GenSet[EmailAddress]]] {
      val buf = mut.Map.empty[String, mut.Set[EmailAddress]]

      def done: GenMap[String, GenSet[EmailAddress]] = buf

      def cont(cursor: Cursor): Boolean = {
        val id = cursor.getString(0)
        val email = cursor.getString(1)
        if ((id ne null) && (email ne null)) {
          val a = EmailAddress(email).normalized
          if (a.isDefined) buf.getOrElseUpdate(id, mut.HashSet.empty) += a.get
        }
        buf.size < limit
      }
    })

    maybeLimit.fold2(emailAddressesCache cached loading(Int.MaxValue), loading)
  }

  private lazy val emailAddressesCache = new FutureCache[GenMap[String, GenSet[EmailAddress]]]

  private def load[A](uri: Uri, ordering: Option[String], selection: Option[String], projection: String*)(sink: Sink[A]): Future[A] = shareContactsPref().map {
    case false =>
      sink.done
    case true =>
      val cursor = context.getContentResolver.query(uri, projection.toArray, selection.orNull, null, ordering.orNull)
      if (cursor == null) sink.done
      else try {
        val size = cursor.getCount

        if (cursor.moveToFirst()) while (sink.cont(cursor) && cursor.moveToNext()) ()

        sink.done
      } finally cursor.close()
  }(Threading.BlockingIO)

  def onAddressBookUploaded(ab: AddressBook, result: Seq[(UserId, Set[ContactId])]): Future[Unit] = {
    val pymk = result.map(_._1)
    def onWire = result.flatIterator

    verbose(s"social graph search found ${result.iterator.map(_._2.size).sum} contact(s) on wire and ${pymk.size} PYMK")

    for {
      _ <- storage(AddressBook.save(ab)(_)).future
      _ <- storage(ContactsOnWireDao.insertOrIgnore(onWire)(_)).future
      _ <- users.syncNotExistingOrExpired(pymk)
      _ <- usersStorage.updateOrCreateAll2(pymk, (id, existing) => existing.getOrElse(UserData(id, "")).copy(relation = Relation.First))
      _ <- Future(contactsOnWire.mutate(_ ++ onWire))
      _ <- lastUploadTime := Some(now)
      _ <- addressBookVersionOfLastUpload := Some(CurrentAddressBookVersion)
    } yield ()
  }
}

object ContactsService {
  private implicit val logTag: LogTag = logTagFor[ContactsService]
  val CurrentAddressBookVersion = 3
  val InitialContactsBatchSize = 101

  case class UnifiedContacts(contacts: GenMap[ContactId, Contact], users: Map[UserId, UserData], sorted: Vector[Either[UserId, ContactId]], groupedByInitial: SeqMap[String, IndexedSeq[Int]], topContactsOnWire: TopContactsOnWire)

  case class TopContactsOnWire(contacts: Vector[ContactId], totalCount: Int)

  val Phones = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
  val Emails = ContactsContract.CommonDataKinds.Email.CONTENT_URI
  val Contacts = ContactsContract.Contacts.CONTENT_URI

  object Col {
    val RowId = BaseColumns._ID
    val ContactId = ContactsContract.RawContactsColumns.CONTACT_ID
    val PhoneNumber = ContactsContract.CommonDataKinds.Phone.NUMBER
    val EmailAddress = ContactsContract.CommonDataKinds.Email.ADDRESS
    val Name = ContactsContract.ContactNameColumns.DISPLAY_NAME_PRIMARY
    val NameSource = ContactsContract.ContactNameColumns.DISPLAY_NAME_SOURCE
    val SortKeyPrimary = ContactsContract.ContactNameColumns.SORT_KEY_PRIMARY
    val SortKeyAlternative = ContactsContract.ContactNameColumns.SORT_KEY_ALTERNATIVE
    val SortKey = SortKeyPrimary
    val Visible = ContactsContract.ContactsColumns.IN_VISIBLE_GROUP
    val InDefaultDirectory = ContactsContract.ContactsColumns.IN_DEFAULT_DIRECTORY
  }

  lazy val Visible = if (SDK_INT >= LOLLIPOP) Some(s"${Col.Visible} = 1 OR ${Col.InDefaultDirectory} = 1") else None
  lazy val OrderBySortKey = s"${Col.SortKey} COLLATE LOCALIZED ASC"

  val PrefKey = "PREF_KEY_PRIVACY_CONTACTS"

  private[service] val zUserAndTimeOfLastCheck = new AtomicReference((AccountId(), Instant.EPOCH))

  def atMostOncePer(id: AccountId, checkInterval: FiniteDuration)(asyncEffect: => Future[Unit]): Future[Unit] = {
    val previous = zUserAndTimeOfLastCheck.get
    if ((id != previous._1 || (checkInterval elapsedSince previous._2)) && zUserAndTimeOfLastCheck.compareAndSet(previous, (id, now))) asyncEffect
    else Future.failed(MayNotYetCheckAgainException)
  }

  object MayNotYetCheckAgainException extends RuntimeException with NoStackTrace
}

private trait Sink[A] { // poor man's iteratee
  def cont(c: Cursor): Boolean // true: continue, false: early abort
  def done: A
}
