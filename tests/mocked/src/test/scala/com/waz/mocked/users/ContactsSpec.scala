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
package com.waz.mocked.users

import com.waz.api.User.ConnectionStatus
import com.waz.api.impl.ErrorResponse
import com.waz.api.{Contacts, _}
import com.waz.api._
import com.waz.content.{GlobalDatabase, ZmsDatabase}
import com.waz.mocked.PushBehaviour.NoPush
import com.waz.mocked.{MockBackend, PushBehaviour, SystemTimeline}
import com.waz.model.Contact
import com.waz.model.AccountData.AccountDataDao
import com.waz.model._
import com.waz.service.ContactsService.PrefKey
import com.waz.service.PreferenceServiceImpl.uiPreferencesFrom
import com.waz.service.{SearchKey, Timeouts, ZMessaging}
import com.waz.sync.client.AddressBookClient.UserAndContactIds
import com.waz.sync.client.InvitationClient.ConfirmedInvitation
import com.waz.testutils.HasId._
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import com.waz.testutils._
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.znet.ZNetClient._
import com.waz.{api => API}
import org.scalatest.matchers.Matcher
import org.scalatest.{FeatureSpec, OptionValues}
import org.threeten.bp.Instant
import org.threeten.bp.Instant.now

import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.language.{implicitConversions, postfixOps}

class ContactsSpec extends FeatureSpec with OptionValues with MockedClientApiSpec with MockBackend {
  import DefaultPushBehaviour.Implicit

  feature("Listing users and contacts") {
    scenario("Phone has no contacts. There are some users.")(afterInitialization { contacts =>
      soon {
        idsOf(contacts) shouldEqual ids(uII, uI)

        contacts(0).getUser.getDisplayName shouldEqual uII.name.value
        contacts(0).getUser.getEmail shouldEqual uII.email.value.str
        contacts(0).getUser.getPhone shouldEqual uII.phone.value.str

        contacts(1).getUser.getDisplayName shouldEqual uI.name.value
        contacts(1).getUser.getEmail shouldEqual uI.email.value.str
        contacts(1).getUser.getPhone shouldBe empty
      }
    })

    scenario("Blocked users are not shown")(afterInitialization { contacts =>
      soon { idsOf(contacts) shouldEqual ids(uII, uI) }
      contacts(0).getUser.block()
      soon { idsOf(contacts) should contain.only(Left(uI.id)) }
      addConnection(uIV.id, SystemTimeline)
      soon { idsOf(contacts) shouldEqual ids(uI, uIV) }
      contacts(1).getUser.block()
      soon { idsOf(contacts) should contain.only(Left(uI.id)) }
    })

    scenario("Pending users are shown") {
      addIncomingConnectionRequest(uIII.id)
      afterInitialization { contacts =>
        soon { idsOf(contacts) shouldEqual ids(uII, uI, uIII) }
        addOutgoingConnectionRequest(uDim.id)
        soon { idsOf(contacts) shouldEqual ids(uDim, uII, uI, uIII) }
      }
    }

    scenario("Phone has contacts. Sharing is disabled.") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr))

      whileSharingIsDisabled(withInitializedApi {
        val contacts = api.getContacts
        idsOf(contacts) shouldEqual ids(uII, uI) soon
      })
    }

    scenario("Phone has contacts. No matches from backend (but locally).") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr))

      afterInitialization { contacts =>
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, cIV, cV)

          val expected = Vector(Right(cVI), Left(uII), Left(uI), Right(cVII), Right(cIV), Right(cV))
          expected.indices foreach(i => contacts.get(i) should containTheSameDataAs(expected(i)))
        }

        addConnection(uIV.id, SystemTimeline)
        soon(dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV))
      }
    }

    scenario("Phone has contacts. There are matches from backend and locally.") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = true)

      afterInitialization { contacts =>
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV)

          contacts.getInitials.asScala.toList shouldEqual List("A", "D", "I", "L")
          contacts.getInitials.asScala.toList.map(contacts.getNumberOfContactsForInitial) shouldEqual List(1, 1, 1, 2)
          contacts.getNumberOfContactsForInitial("X") shouldBe 0
        }
      }
    }

    scenario("Contacts matching a blocked user are not shown") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr, cHWD, cWHD), someOfThemAreOnWire = true)

      afterInitialization { contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, cHWD, uI, cVII, uIV, cWHD) }
        contacts(1).getUser.block()
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, cHWD, uI, cVII, uIV, cWHD) }
        users += uDim.id -> uDim
        addConnection(uDim.id, SystemTimeline)
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uDim, uI, cVII, uIV) }
        contacts(1).getUser.block()
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uI, cVII, uIV) }
      }
    }
  }

  feature("Inviting contacts") {
    scenario("Inviting someone who is not yet on Wire.") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = true)
      postInvitationResponse = successful(invited(cVII))

      afterInitialization({ implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV) }
        contactNamed(cVII.name).hasBeenInvited shouldBe false
        val (contact, spy) = invite(contactNamed(cVII.name))

        soon {
          contact.hasBeenInvited shouldBe true
          spy.numberOfTimesCalled should be >= 1
        }

        spy.reset()
        users += uVII.id -> uVII
        autoConnect(uVII.id, SystemTimeline)

        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, uVII, uIV)
          contact.hasBeenInvited shouldBe false
          spy.numberOfTimesCalled should be >= 1
        }
      }, keepUserAfterwards = true)

      afterInitialization { implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, uVII, uIV) }
      }
    }

    scenario("Inviting someone who is not yet on Wire and accepts while inviter's app is not running.") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = true)
      postInvitationResponse = successful(invited(cVII))

      afterInitialization({ implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV) }
        contactNamed(cVII.name).hasBeenInvited shouldBe false
        val (contact, spy) = invite(contactNamed(cVII.name))

        soon {
          contact.hasBeenInvited shouldBe true
          spy.numberOfTimesCalled should be >= 1
        }
      }, keepUserAfterwards = true)

      users += uVII.id -> uVII
      autoConnect(uVII.id, SystemTimeline)(NoPush)

      afterInitialization { implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, uVII, uIV) }
      }
    }

    scenario("Top contacts includes only users who are on wire") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = true)

      afterInitialization({ implicit contacts =>
        soon { contacts.getTotalContactsOnWireCount() shouldEqual 2 }
        soon {
          contacts.getTop10ContactsOnWire().asScala.map(_.getDisplayName) should contain allOf (cII.name, cIV.name)
        }
      })
    }

    scenario("Inviting someone who is already on Wire.") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = false)
      val promisedInvitation = Promise[Either[ErrorResponse, Either[UserId, ConfirmedInvitation]]]
      val promisedConnection = Promise[Either[ErrorResponse, UserConnectionEvent]]
      postInvitationResponse = promisedInvitation.future
      loadConnectionResponse = promisedConnection.future

      afterInitialization { implicit contacts =>
        val convs = api.getConversations
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, cIV, cV)
          convs should have size 2
        }
        contactNamed(cV.name).hasBeenInvited shouldBe false
        val (contact, spy) = invite(contactNamed(cV.name))

        soon {
          spy.numberOfTimesCalled should be >= 1
          contact.hasBeenInvited shouldBe true
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, cIV, cV)
        }

        spy.reset()
        promisedInvitation.success(Right(Left(uIV.id)))

        soon {
          spy.numberOfTimesCalled shouldBe 0
          contact.hasBeenInvited shouldBe true
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, cIV, cV)
          convs should have size 2
        }

        spy.reset()
        promisedConnection.success(Right(addOutgoingConnectionRequest(uIV.id, SystemTimeline)(PushBehaviour.NoPush))) // connection request instead of invitation

        soon {
          spy.numberOfTimesCalled should be >= 1
          contact.hasBeenInvited shouldBe false
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV, cIV)
          contacts.get(4).getUser.getConnectionStatus shouldEqual ConnectionStatus.PENDING_FROM_USER
          convs should have size 3
          convs.get(0).getName shouldEqual uIV.name.value
          convs.get(0).getType shouldEqual IConversation.Type.WAIT_FOR_CONNECTION
        }

        spy.reset()
        acceptConnection(uIV.id, SystemTimeline) // invitee accepts request

        soon {
          spy.numberOfTimesCalled shouldBe 0
          contact.hasBeenInvited shouldBe false
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV)
          contacts.get(4).getUser.getConnectionStatus shouldEqual ConnectionStatus.ACCEPTED
          convs.get(0).getType shouldEqual IConversation.Type.ONE_TO_ONE
        }
      }
    }

    scenario("Inviting someone who is already on Wire, in a subsequent session") {
      givenSomeContacts(Seq(cII, cVI, cVII, cCr), someOfThemAreOnWire = false)
      val promisedInvitation = Promise[Either[ErrorResponse, Either[UserId, ConfirmedInvitation]]]
      val promisedConnection = Promise[Either[ErrorResponse, UserConnectionEvent]]
      postInvitationResponse = promisedInvitation.future
      loadConnectionResponse = promisedConnection.future

      afterInitialization({ implicit contacts =>
        val convs = api.getConversations
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII)
          convs should have size 2
        }
        whilePaused {
          givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = false)
        }
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, cIV, cV)
        }

        invite(contactNamed(cV.name))
        promisedInvitation.success(Right(Left(uIV.id)))
        promisedConnection.success(Right(addOutgoingConnectionRequest(uIV.id, SystemTimeline)(PushBehaviour.NoPush))) // connection request instead of invitation

        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV, cIV)
          contacts.get(4).getUser.getConnectionStatus shouldEqual ConnectionStatus.PENDING_FROM_USER
          convs should have size 3
          convs.get(0).getType shouldEqual IConversation.Type.WAIT_FOR_CONNECTION
        }
      }, keepUserAfterwards = true)

      afterInitialization { implicit contacts =>
        val convs = api.getConversations
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV, cIV)
          contacts.get(4).getUser.getConnectionStatus shouldEqual ConnectionStatus.PENDING_FROM_USER
          convs should have size 3
          convs.get(0).getType shouldEqual IConversation.Type.WAIT_FOR_CONNECTION
        }
        acceptConnection(uIV.id, SystemTimeline) // invitee accepts request

        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV)
          contacts.get(4).getUser.getConnectionStatus shouldEqual ConnectionStatus.ACCEPTED
          convs.get(0).getType shouldEqual IConversation.Type.ONE_TO_ONE
        }
      }
    }

    scenario("Invitation fails.") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = true)
      val promisedInvitation = Promise[Either[ErrorResponse, Either[UserId, ConfirmedInvitation]]]
      postInvitationResponse = promisedInvitation.future

      afterInitialization { implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV) }
        val (contact, spy) = invite(contactNamed(cVII.name))

        soon {
          spy.numberOfTimesCalled should be >= 1
          contact.hasBeenInvited shouldBe true
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV)
        }

        spy.reset()
        promisedInvitation.success(Left(ErrorResponse(418, "I'm a teapot", "and you?")))

        soon {
          spy.numberOfTimesCalled should be >= 1
          contact.hasBeenInvited shouldBe false
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV)
        }
      }
    }

    scenario("Invitation was sent in previous session.") {
      givenAPreviousInvitationAt(now minus 1.day)
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr), someOfThemAreOnWire = true)

      afterInitialization { implicit contacts =>
        lazy val contact = contactNamed(cVII.name)
        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, uI, cVII, uIV)
          contact.hasBeenInvited shouldBe true
        }

        val spy = UpdateSpy(contact)
        postInvitationResponse = successful(invited(cVII))
        contact.getContactMethods.asScala.head.invite("#inB4some1elseinvitesU", null)

        soon {
          spy.numberOfTimesCalled shouldBe 0
          contact.hasBeenInvited shouldBe true
        }
      }
    }

    scenario("Inviting a contact also updates other contacts that also have the contact method that was used for the invitation") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr, cHWD, cWHD, cHWDt), someOfThemAreOnWire = true)
      postInvitationResponse = successful(invited(cVII))

      afterInitialization { implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, cHWD, cHWDt, uI, cVII, uIV, cWHD) }
        val c1 = contactNamed(cWHD.name)
        val s1 = UpdateSpy(c1)
        val c2 = contactNamed(cHWD.name)
        val s2 = UpdateSpy(c2)
        val c3 = contactNamed(cHWDt.name)
        val s3 = UpdateSpy(c3)
        c1.hasBeenInvited shouldBe false
        c2.hasBeenInvited shouldBe false
        c3.hasBeenInvited shouldBe false
        c2.getContactMethods.asScala.find(_.getKind == ContactMethod.Kind.SMS).value.invite("meep", null)

        soon {
          c1.hasBeenInvited shouldBe true
          s1.numberOfTimesCalled should be >= 1
          c2.hasBeenInvited shouldBe true
          s2.numberOfTimesCalled should be >= 1
          c3.hasBeenInvited shouldBe false
          s3.numberOfTimesCalled shouldBe 0
        }

        s1.reset()
        s2.reset()
        s3.reset()
        users += uDim.id -> uDim
        autoConnect(uDim.id, SystemTimeline)

        soon {
          dehashed(idsOf(contacts)) shouldEqual ids(cVI, uDim, uII, cHWDt, uI, cVII, uIV)
          c1.hasBeenInvited shouldBe false
          s1.numberOfTimesCalled should be >= 1
          c2.hasBeenInvited shouldBe false
          s2.numberOfTimesCalled should be >= 1
          c3.hasBeenInvited shouldBe false
          s3.numberOfTimesCalled shouldBe 0
        }
      }
    }
  }

  feature("Searching") {
    scenario("Searching the unified contacts list") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr, cHWD, cWHD, cHWDt), someOfThemAreOnWire = true)
      afterInitialization { implicit contacts =>
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, cHWD, cHWDt, uI, cVII, uIV, cWHD) }

        contacts.search("l")
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVII, uIV) }
        contacts.search("ly")
        soon { dehashed(idsOf(contacts)) shouldEqual ids(uIV) }
        contacts.search("lyb")
        soon { dehashed(idsOf(contacts)) shouldBe empty }
        contacts.search("l")
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVII, uIV) }
        contacts.search("lo")
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVII, uIV) }
        contacts.search("")
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, cHWD, cHWDt, uI, cVII, uIV, cWHD) }
      }
    }

    scenario("Searching only external contacts") {
      givenSomeContacts(Seq(cII, cIV, cV, cVI, cVII, cCr, cHWD, cWHD, cHWDt), someOfThemAreOnWire = true)
      afterInitialization { contacts =>
        val filtered = api.search().getContacts("mode")
        soon { dehashed(idsOf(filtered)) shouldEqual ids(cVI, cVII) }
        soon { dehashed(idsOf(contacts)) shouldEqual ids(cVI, uII, cHWD, cHWDt, uI, cVII, uIV, cWHD) }

        soon {
          filtered.get(1).getDetails.getDisplayName shouldEqual cVII.name
          contacts.get(5).getDetails.getDisplayName shouldEqual cVII.name
        }

        filtered.search("")
        soon { dehashed(idsOf(filtered)) shouldEqual ids(cVI, cHWD, cHWDt, cVII, cWHD) }
        filtered.search("s")
        soon {
          dehashed(idsOf(filtered)) shouldEqual ids(cHWD, cHWDt, cWHD)
          contacts.get(5).getDetails.getDisplayName shouldEqual cVII.name
        }
        filtered.search("mixo")
        soon {
          dehashed(idsOf(filtered)) shouldBe empty
          contacts.get(5).getDetails.getDisplayName shouldEqual cVII.name
        }

        val filtered2 = api.search().getContacts("")
        soon { dehashed(idsOf(filtered2)) shouldEqual ids(cVI, cHWD, cHWDt, cVII, cWHD) }
      }
    }
  }

  override val initBehaviour = InitManually

  def afterInitialization[A](f: Contacts => A, keepUserAfterwards: Boolean = false): A = {
    val before = keepUser
    keepUser = keepUserAfterwards
    try withInitializedApi(f(api.getContacts))
    finally keepUser = before
  }

  override protected def beforeEach(): Unit = {
    Seq(self, uI, uII, uIII, uIV, uDim) foreach (u => users += u.id -> u)
    Seq(uI, uII) foreach (u => addConnection(u.id))
    super.beforeEach()
  }

  override lazy val timeouts: Timeouts = new Timeouts {
    override val contacts: Contacts = new Contacts {
      override def uploadCheckInterval: Timeout = 0.seconds
      override def userMatchingInterval: Timeout = 0.seconds
    }
  }

  override lazy val selfUserId = UserId("u/ME")
  val self = UserInfo(selfUserId, Some("Altered Scale"), email = Some(EmailAddress("altered@sca.les")))
  val uI   = UserInfo(UserId("u/I"), Some("Ionian"), email = Some(EmailAddress("ionian@sca.les")))
  val uII  = UserInfo(UserId("u/II"), Some("Dorian"), phone = Some(PhoneNumber("+30123456789")), email = Some(EmailAddress("dorian@sca.les")))
  val uIII = UserInfo(UserId("u/III"), Some("Phrygian"), email = Some(EmailAddress("phrygian@sca.les")))
  val uIV  = UserInfo(UserId("u/IV"), Some("Lydian b7"), phone = Some(PhoneNumber("+30111111")), email = Some(EmailAddress("lydian@sca.les")))
  val uVII = UserInfo(UserId("u/VII"), Some("Locrian"), email = Some(EmailAddress("locrian@sca.les")))
  val uDim = UserInfo(UserId("u/Dim"), Some("Diminished"), phone = Some(PhoneNumber("+30333221")))

  lazy val cII   = Contact(ContactId("c/II"), "Dorian Mode", NameSource.StructuredName, "Dorian Mode", SearchKey("Dorian Mode"), Set(), Set(EmailAddress("dorian@sca.les")))
  lazy val cIV   = Contact(ContactId("c/IV"), "Lydian b7 Mode", NameSource.StructuredName, "Lydian b7 Mode", SearchKey("Lydian b7 Mode"), Set(), Set(EmailAddress("lydian@sca.les")))
  lazy val cV    = Contact(ContactId("c/V"), "Mixolydian #11 Mode", NameSource.Nickname, "Mixolydian #11 Mode", SearchKey("Mixolydian #11 Mode"), Set(PhoneNumber("+30111111")), Set(EmailAddress("mixolydian.sharp.eleven@sca.les")))
  lazy val cVI   = Contact(ContactId("c/VI"), "Aeolian Mode", NameSource.StructuredName, "Aeolian Mode", SearchKey("Aeolian Mode"), Set(PhoneNumber("+30123444444")), Set())
  lazy val cVII  = Contact(ContactId("c/VII"), "Locrian Mode", NameSource.StructuredName, "Locrian Mode", SearchKey("Locrian Mode"), Set(), Set(EmailAddress("locrian@sca.les")))
  lazy val cCr   = Contact(ContactId("c/Cr"), "Chromatic", NameSource.Other, "Chromatic", SearchKey("Chromatic"), Set(), Set(EmailAddress("chromatic@sca.les")))
  lazy val cHWD  = Contact(ContactId("c/HWD"), "Half-Whole-Diminished Scale", NameSource.Nickname, "Half-Whole-Diminished Scale", SearchKey("Half-Whole-Diminished Scale"), Set(PhoneNumber("+30333221")), Set(EmailAddress("hwd@sca.les")))
  lazy val cWHD  = Contact(ContactId("c/WHD"), "Whole-Half-Diminished Scale", NameSource.Nickname, "Whole-Half-Diminished Scale", SearchKey("Whole-Half-Diminished Scale"), Set(PhoneNumber("+30333221")), Set(EmailAddress("whd@sca.les")))
  lazy val cHWDt = Contact(ContactId("c/HWDt"), "Half-Whole-Diminished Scale Transposed", NameSource.Nickname, "Half-Whole-Diminished Scale Transposed", SearchKey("Half-Whole-Diminished Scale Transposed"), Set(PhoneNumber("+30444332")), Set(EmailAddress("hwd@sca.les")))

  lazy val selfUser = api.getSelf

  override def email: String = self.email.get.str

  override protected def afterEach(): Unit = {
    super.afterEach()
    ZMessaging.currentAccounts = null
    resetMockedBackend()
    prepareContacts()
    keepUser = false
    postAddressBookResponse = Nil
    postInvitationResponse = failed(new NotImplementedError())
    loadConnectionResponse = failed(new NotImplementedError())
  }

  def givenSomeContacts(contacts: Seq[Contact], someOfThemAreOnWire: Boolean = false): Unit = {
    prepareContacts(contacts:_*)
    postAddressBookResponse =
      if (! someOfThemAreOnWire) Nil
      else Vector(
        uII -> Set(cII),
        uIV -> Set(cIV, cV)
      ).map { case (k, v) => (k.id, v.map(c => hash(c.id)))}
  }

  def givenAPreviousInvitationAt(i: Instant): Unit = {
    val zuser = AccountData(EmailAddress(email), password).copy(activated = true)
    val globalDB = new GlobalDatabase(context)
    try Await.result(globalDB(AccountDataDao.insertOrReplace(zuser)(_)), 10.seconds) finally globalDB.close
    val userDB = new ZmsDatabase(zuser.id, context)
    try Await.result(userDB(InvitedContacts.invited(Seq(hash(cVII.id)), i)(_)), 10.seconds) finally userDB.close
  }

  def invite(contact: ContactDetails) = {
    val spy = UpdateSpy(contact)
    contact.getContactMethods.asScala.head.invite("#inB4some1elseinvitesU", null)
    (contact, spy)
  }

  def contactNamed(name: String)(implicit contacts: Contacts) = contacts.asScala.find(c => Option(c.getDetails).exists(_.getDisplayName == name)).value.getDetails

  override def deleteUserAfterLogout(): Unit = if (! keepUser) super.deleteUserAfterLogout()
  override def postAddressBook(a: AddressBook): ErrorOrResponse[Seq[UserAndContactIds]] = CancellableFuture.delayed(clientDelay * 5)(Right(postAddressBookResponse))(Threading.Background)
  override def postInvitation(i: Invitation): ErrorOrResponse[Either[UserId, ConfirmedInvitation]] = CancellableFuture.delay(clientDelay).flatMap(_ => CancellableFuture.lift(postInvitationResponse))(Threading.Background)
  override def loadConnection(other: UserId): ErrorOrResponse[UserConnectionEvent] = CancellableFuture.delay(clientDelay).flatMap(_ => CancellableFuture.lift(loadConnectionResponse))(Threading.Background)

  @volatile var keepUser = false
  @volatile var postAddressBookResponse: Seq[UserAndContactIds] = Nil
  @volatile var postInvitationResponse: Future[Either[ErrorResponse, Either[UserId, ConfirmedInvitation]]] = failed(new NotImplementedError())
  @volatile var loadConnectionResponse: Future[Either[ErrorResponse, UserConnectionEvent]] = failed(new NotImplementedError())

  def invited(contact: Contact) = Right(Right(ConfirmedInvitation(InvitationId(), contact.name, contact.emailAddresses.headOption.toLeft(contact.phoneNumbers.head), now)))

  lazy val allContacts = Seq(cII, cIV, cV, cVI, cVII, cCr, cHWD, cWHD, cHWDt)

  implicit def userInfoIsUid(u: UserInfo): Either[UserId, ContactId] = Left(u.id)
  implicit def contactIsContactId(c: Contact): Either[UserId, ContactId] = Right(c.id)

  def ids(os: Either[UserId, ContactId]*) = os.toVector
  def dehashed(ids: Vector[Either[UserId, ContactId]]) = ids.map(_.right.map(reverseMapping))
  def hash(c: ContactId) = ContactId(sha2(c.str))

  lazy val reverseMapping: Map[ContactId, ContactId] = allContacts.map(c => ContactId(sha2(c.id.str)) -> c.id)(breakOut)

  def containTheSameDataAs(userOrContact: Either[UserInfo, Contact]): Matcher[API.Contact] = userOrContact.fold(
    user =>
      (eql(user.name.value) compose ((_: API.Contact).getUser.getDisplayName)) and
      (be(null) compose ((_: API.Contact).getDetails)),
    contact =>
      (eql(contact.name) compose ((_: API.Contact).getDetails.getDisplayName)) and
      (eql(contact.initials) compose ((_: API.Contact).getDetails.getInitials)) and
      (eql(contact.emailAddresses.map(_.str).toSeq ++ contact.phoneNumbers.map(_.str)) compose ((_: API.Contact).getDetails.getContactMethods.asScala.map(_.getStringRepresentation).toSeq)) and
      (be(null) compose ((_: API.Contact).getUser)))

  def eql[A](a: A): Matcher[A] = equal(a)

  def whileSharingIsDisabled[A](f: => A): A = {
    val uiPrefs = uiPreferencesFrom(context)
    val previousValue = uiPrefs.getBoolean(PrefKey, true)
    uiPrefs.edit().putBoolean(PrefKey, false).commit()
    try f finally uiPrefs.edit().putBoolean(PrefKey, previousValue).commit()
  }
}
