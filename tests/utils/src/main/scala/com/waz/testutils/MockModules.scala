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
package com.waz.testutils

import android.net.Uri
import com.koushikdutta.async.http.WebSocket
import com.waz.api.Message
import com.waz.content.{Database, GlobalDatabase}
import com.waz.model.MessageData.MessageDataDao
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.model.otr.{Client, ClientId}
import com.waz.service._
import com.waz.service.otr.CryptoBoxService
import com.waz.service.push.{WebSocketClientService, WebSocketClientServiceImpl}
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.OtrClient
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.ui.UiModule
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.returning
import com.waz.znet.AuthenticationManager.{Cookie, Token}
import com.waz.znet.ZNetClient.{EmptyAsyncClientImpl, ErrorOrResponse}
import com.waz.znet._
import com.wire.cryptobox.PreKey
import net.hockeyapp.android.Constants
import org.robolectric.Robolectric

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class MockGlobalModule(dbSuffix: String = Random.nextInt().toHexString) extends GlobalModuleImpl(Robolectric.application, BackendConfig.StagingBackend) { global =>
  override lazy val client: AsyncClientImpl = new EmptyAsyncClientImpl(TestClientWrapper())
  override lazy val clientWrapper: Future[ClientWrapper] = TestClientWrapper()
  override lazy val storage: Database = new GlobalDatabase(context, dbSuffix)

  ZMessaging.context = context
  if (ZMessaging.currentGlobal == null) ZMessaging.currentGlobal = this
  Constants.loadFromContext(context)
  override lazy val factory = new MockZMessagingFactory(this)

  override lazy val loginClient: LoginClient = new LoginClientImpl(client, backend) {
    override def access(cookie: Cookie, token: Option[Token]) = CancellableFuture.successful(Right((Token("", "", Int.MaxValue), Some(Cookie("")))))
  }
}

class MockZMessagingFactory(global: MockGlobalModule) extends ZMessagingFactory(global) {
  override def zmessaging(teamId: Option[TeamId], clientId: ClientId, user: UserModule, st: StorageModule, cb: CryptoBoxService): ZMessaging = super.zmessaging(teamId, clientId, user, st, cb)
}

class MockAccountsService(global: GlobalModuleImpl = new MockGlobalModule) extends AccountsServiceImpl(global) {

  if (ZMessaging.currentAccounts == null) ZMessaging.currentAccounts = this

  override private[waz] implicit val ec: EventContext = new EventContext {
    onContextStart()
  }
}

class MockAccountManager(override val accounts: AccountsServiceImpl = new MockAccountsService)(implicit ec: EventContext = EventContext.Global) extends AccountManager(AccountId(), accounts.global, accounts) {
  accounts.accountMap.put(id, this)

  val _zmessaging = Signal[Option[ZMessaging]]()

  override val zmessaging: Signal[Option[ZMessaging]] = _zmessaging

  def set(zms: MockZMessaging) = {
    _zmessaging ! Some(zms)
    accounts.activeAccountPref := Some(id)
  }
}

class MockUserModule(val mockAccount: MockAccountManager = new MockAccountManager(), userId: UserId = UserId()) extends UserModule(userId, mockAccount)

class MockZMessaging(val mockUser: MockUserModule = new MockUserModule(), teamId: Option[TeamId] = None, clientId: ClientId = ClientId()) extends ZMessaging(teamId, clientId, mockUser, null, null) { zms =>
  def this(selfUserId: UserId) = this(new MockUserModule(userId = selfUserId), None, ClientId())
  def this(selfUserId: UserId, clientId: ClientId) = this(new MockUserModule(userId = selfUserId), None, clientId)
  def this(selfUserId: UserId, teamId: TeamId, clientId: ClientId) = this(new MockUserModule(userId = selfUserId), Some(teamId), clientId)
  def this(account: MockAccountManager, selfUserId: UserId) = this(new MockUserModule(account, userId = selfUserId), None, ClientId())

  override lazy val sync: SyncServiceHandle = new EmptySyncService
  import Threading.Implicits.Background

  var timeout = 5.seconds

  storage.usersStorage.put(selfUserId, UserData(selfUserId, None, "test name", Some(EmailAddress("test@test.com")), None, searchKey = SearchKey("test name"), connection = ConnectionStatus.Self, handle = Some(Handle("test_username"))))

  override lazy val otrClient: OtrClient = new OtrClient(zNetClient) {
    var client = Option.empty[Client]

    override def loadClients(): ErrorOrResponse[Seq[Client]] = CancellableFuture.successful(Right(client.toSeq))

    override def postClient(userId: AccountId, client: Client, lastKey: PreKey, keys: Seq[PreKey], password: Option[String]): ErrorOrResponse[Client] = {
      this.client = Some(client)
      CancellableFuture.successful(Right(client))
    }
  }

  override lazy val websocket: WebSocketClientService = new WebSocketClientServiceImpl(context, accountId, accounts, zNetClient, auth, network, backend, clientId, timeouts, pushToken) {
    override private[waz] def createWebSocketClient(clientId: ClientId): WebSocketClient = new WebSocketClient(context, accountId, zNetClient.client.asInstanceOf[AsyncClientImpl], Uri.parse("http://"), auth) {
      override protected def connect(): CancellableFuture[WebSocket] = CancellableFuture.failed(new Exception("mock"))
    }
  }

  def insertConv(conv: ConversationData) = Await.result(convsStorage.insert(conv), timeout)
  def getConv(id: ConvId): Option[ConversationData] = Await.result(convsContent.convById(id), timeout)
  def getConv(id: RConvId): Option[ConversationData] = Await.result(convsContent.convByRemoteId(id), timeout)
  def getConv(id: UserId): Option[ConversationData] = getConv(ConvId(id.str))
  def listConvs = Await.result(convsStorage.list, timeout)
  def deleteAllConvs() = Await.result(
    for {
      cs <- convsStorage.list.map(_.map(_.id))
      _ <- Future.traverse(cs) { convsStorage.remove }
      _ <- Future.traverse(cs) { messagesStorage.deleteAll }
    } yield (), timeout)

  def listMembers(conv: ConvId) = Await.result(membersStorage.getByConv(conv), timeout)

  def insertUsers(users: Seq[UserData]) = users map { user => Await.result(usersStorage.addOrOverwrite(user), timeout) }
  def insertUser(user: UserData) = Await.result(usersStorage.addOrOverwrite(user), timeout)
  def getUser(id: UserId) = Await.result(users.getUser(id), timeout)

  def dispatch(es: Event*) = eventPipeline(es)
  def dispatchEvent(es: Event*) = dispatchEvents(es)
  def dispatchEvents(es: Seq[Event]) = Await.result(eventPipeline(es), timeout)
  def connectToUser(user: UserData) = Await.result(connection.connectToUser(user.id, "Hi there", user.name), timeout)

  def addMember(conv: ConvId, user: UserId) = Await.result(membersStorage.add(conv, user), timeout)
  def removeMember(conv: ConvId, user: UserId) = Await.result(membersStorage.remove(conv, user), timeout)
  def listActiveMembers(conv: ConvId) = Await.result(membersStorage.getActiveUsers(conv), timeout).toList


  def addMessage(msg: MessageData) = Await.result(messagesStorage.addMessage(msg), timeout)
  def delMessages(conv: ConvId) = Await.result(messagesStorage.deleteAll(conv), timeout)
  def listMessages(conv: ConvId) = {
    Thread.sleep(100)
    Await.result(storage.db { db => MessageDataDao.list(MessageDataDao.findMessages(conv)(db)).sortBy(_.time) }, timeout)
  }
  def lastMessage(conv: ConvId) = Await.result(messagesStorage.getLastMessage(conv), timeout)
  def lastLocalMessage(conv: ConvId, tpe: Message.Type) = Await.result(messagesStorage.lastLocalMessage(conv, tpe), timeout)
}

class MockUiModule(zmessaging: MockZMessaging) extends UiModule(zmessaging.mockUser.mockAccount.accounts) {
  ZMessaging.currentUi = this
  setCurrent(Some(zmessaging))

  def setCurrent(zms: Option[ZMessaging]) = zms match {
    case Some(z: MockZMessaging) =>
      accounts.accountMap(z.accountId) = z.account
      accounts.activeAccountPref := Some(z.accountId)
    case _ =>
      accounts.activeAccountPref := None
  }
}

object MockUiModule {

  def apply(accounts: AccountsServiceImpl): UiModule = returning(new UiModule(accounts)) { ui =>
    ZMessaging.currentUi = ui
  }

  def apply(zmessaging: MockZMessaging): UiModule = new MockUiModule(zmessaging)
}
