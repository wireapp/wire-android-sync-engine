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
package com.waz.users

import java.util.UUID.randomUUID

import com.waz.api.ZMessagingApi.RegistrationListener
import com.waz.api.impl.{AccentColor, ErrorResponse}
import com.waz.api.{ApiSpec, CredentialsFactory, Self}
import com.waz.client.RegistrationClient
import com.waz.model.AddressBook.ContactHashes
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.provision.{EmailClientSuite, UserProvisioner}
import com.waz.service.{SearchKey, UserModule, ZMessaging, ZMessagingFactory}
import com.waz.sync.client.UserSearchClient.UserSearchEntry
import com.waz.sync.client.{AddressBookClient, UserSearchClient}
import com.waz.testutils._
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.sha2
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet.{AsyncClient, TestClientWrapper}
import org.robolectric.shadows.ShadowContentResolver
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import scala.collection.breakOut
import scala.concurrent.Await
import scala.concurrent.duration._

class AddressBookSpec extends FeatureSpec with Matchers with BeforeAndAfter with ApiSpec with EmailClientSuite { test =>
  @volatile var usersInAddressBook = Seq.empty[UserId]

  override def backendHostname: String = testBackend.baseUrl.stripPrefix("https://")

  lazy val baseName = s"Android Test User ${randomUUID}"

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    ShadowContentResolver.reset()

    val throttled = RateLimit(1.second)
    val emailsToRegister = emails.drop(1)

    val registeredUsers: Map[EmailAddress, UserInfo] = (for {
      (email, n) <- emailsToRegister.zipWithIndex
      _ = println(s"registering $email")
      info <- new UserProvisioner(email.str, password, s"$baseName $n", shouldConnect = false, globalModule).register().right.toSeq
    } yield email -> info)(breakOut)

    def abclient(email: EmailAddress) = new AddressBookClient(znetClientFor(email.str, password))

    val graphUsers = emailsToRegister.drop(1)

    val hashes = Map(
      graphUsers(0) -> Seq(sha2(graphUsers(0).str), sha2("+493012345678")), // should be matched on 2 cards
      graphUsers(1) -> Seq(sha2("+4915324568954")), // matched on 1 card
      graphUsers(2) -> Seq(sha2("something completely unrelated")), // will not be matched
      graphUsers(3) -> Seq(sha2("some nonsense"), sha2(graphUsers(3).str)), // matched on 1 card
      graphUsers(4) -> Seq(sha2(graphUsers(4).str), sha2("unknown@addr.ess")), // should be matched on 3 cards
      graphUsers(5) -> Seq(sha2(graphUsers(5).str))) // will not be matched, but is in each address book entry's address book (friend of friend)

    graphUsers foreach { email =>
      val ab = AddressBook(hashes(email), Seq(ContactHashes(ContactId("0"), Set(sha2(graphUsers(5).str)))))
      Await.result(throttled(abclient(email).postAddressBook(ab)), 5.seconds).right.get
    }

    usersInAddressBook = Seq(0, 1, 3, 4) map graphUsers map registeredUsers map (_.id)

    prepareContacts(
      Contact(ContactId("1"), "C", NameSource.Nickname, "C", SearchKey("C"), Set(PhoneNumber("+493012345678")), Set(graphUsers(0))),
      Contact(ContactId("2"), "D", NameSource.Nickname, "D", SearchKey("D"), Set(PhoneNumber("+4915324568954")), Set()),
      Contact(ContactId("3"), "E", NameSource.Nickname, "E", SearchKey("E"), Set(), Set(graphUsers(2))),
      Contact(ContactId("4"), "F", NameSource.Nickname, "F", SearchKey("F"), Set(), Set(graphUsers(3))),
      Contact(ContactId("5"), "G", NameSource.Nickname, "G", SearchKey("G"), Set(), Set(graphUsers(4))),
      Contact(ContactId("6"), "H", NameSource.Nickname, "H", SearchKey("H"), Set(), Set(EmailAddress("unknown@addr.ess"))),
      Contact(ContactId("7"), "I", NameSource.Nickname, "I", SearchKey("I"), Set(PhoneNumber("+493012345678")), Set()),
      Contact(ContactId("8"), "J", NameSource.Nickname, "J", SearchKey("J"), Set(), Set(EmailAddress("unknown@addr.ess"), graphUsers(4))))

    awaitUi(1.second)
  }

  after {
    api.logout()
    awaitUi(1.second)
  }

  feature("upload address book") {
    scenario("upload address book when registering") {
      emailClient.deleteVerificationEmails()

      var selfUser: Self = null
      var failed = true

      api.register(CredentialsFactory.emailCredentials(emails(0).str, password), "Android Test User A", AccentColor(2), new RegistrationListener {
        override def onRegistrationFailed(code: Int, message: String, label: String): Unit = {
          info(s"failed. code: $code, message: $message, label: $label")
          failed = true
        }
        override def onRegistered(user: Self): Unit = {
          failed = false
          selfUser = user
        }
      })

      withDelay {
        failed shouldEqual false
        selfUser should not be null
        selfUser.isLoggedIn shouldEqual true
        selfUser.accountActivated shouldEqual false
      }

      emailClient.verifyEmail(emails(0).str)(emails(0).str) shouldBe true

      awaitUi(10.seconds) // wait for address book upload that was triggered on verified login
      val search = api.search().getRecommendedPeople(baseName, 30, Array.empty)
      withDelay { search.getAll.map(_.getId).map(UserId) should contain allOf(usersInAddressBook(0), usersInAddressBook(1), usersInAddressBook.drop(2):_*) }(20.seconds)
    }

    scenario("upload address book when logging in with already registered & activated user") {
      login(emails(1).str, password) shouldEqual true

      awaitUi(10.seconds) // wait for address book upload that was triggered on verified login
      val search = api.search().getRecommendedPeople(baseName, 30, Array.empty)
      withDelay { search.getAll.map(_.getId).map(UserId) should contain allOf(usersInAddressBook(0), usersInAddressBook(1), usersInAddressBook.drop(2):_*) }(20.seconds)
    }
  }

  implicit lazy val dispatcher = Threading.Background
  override val autoLogin: Boolean = false
  val uuids = (1 to 8) map (_ => randomUUID())
  val emails = uuids map (id => EmailAddress(s"android.test+$id@wire.com"))

  def newClient = new RegistrationClient(new AsyncClient(wrapper = TestClientWrapper()), testBackend)

  override lazy val zmessagingFactory: ZMessagingFactory = new ZMessagingFactory(globalModule) {
    override def zmessaging(clientId: ClientId, user: UserModule): ZMessaging = new ApiZMessaging(clientId, user) {
      override lazy val userSearchClient: UserSearchClient = new UserSearchClient(netClient) {
        // server response will not update immediately with newly registered users, thus we fail the sync request
        // which will cause the search to use local suggestions, which should find the contacts because
        // address book upload will initiate a sync for the matched users
        override def graphSearch(query: SearchQuery, limit: Int): ErrorOrResponse[Seq[UserSearchEntry]] = CancellableFuture.successful(Left(ErrorResponse.InternalError))
      }
    }
  }
}
