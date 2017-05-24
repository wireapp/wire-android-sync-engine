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
package com.waz.mocked

import android.content.Context
import android.net.Uri
import com.waz.ZLog._
import com.waz.api.impl.{Credentials, ErrorResponse, PhoneCredentials}
import com.waz.api.{OtrClient => _, _}
import com.waz.cache.LocalData
import com.waz.client.RegistrationClient
import com.waz.client.RegistrationClient.ActivateResult
import com.waz.content.UserPreferences
import com.waz.model.UserData._
import com.waz.model._
import com.waz.model.otr.{Client, ClientId, SignalingKey}
import com.waz.service
import com.waz.service._
import com.waz.service.call.DefaultFlowManagerService
import com.waz.service.push.PushServiceImpl
import com.waz.sync.client.AddressBookClient.UserAndContactIds
import com.waz.sync.client.ConversationsClient.ConversationResponse
import com.waz.sync.client.ConversationsClient.ConversationResponse.ConversationsResult
import com.waz.sync.client.InvitationClient.ConfirmedInvitation
import com.waz.sync.client.MessagesClient.OtrMessage
import com.waz.sync.client.OtrClient.{ClientKey, MessageResponse}
import com.waz.sync.client.UserSearchClient.UserSearchEntry
import com.waz.sync.client.VoiceChannelClient.JoinCallFailed
import com.waz.sync.client._
import com.waz.testutils.TestUserPreferences
import com.waz.threading.CancellableFuture.successful
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.znet.AuthenticationManager._
import com.waz.znet.LoginClient.LoginResult
import com.waz.znet.ZNetClient._
import com.waz.znet._
import com.wire.cryptobox.PreKey
import org.scalatest.{Alerting, Informing, Suite}

import scala.concurrent.Future
import scala.util.Random

trait MockedClientSuite extends ApiSpec with MockedClient with MockedWebSocket with MockedGcm { suite: Suite with Alerting with Informing =>
  private implicit val logTag: LogTag = logTagFor[MockedClientSuite]

  @volatile private var pushService = Option.empty[PushServiceImpl]
  @volatile protected var keyValueStoreOverrides = Map.empty[String, Option[String]]

  class MockedStorageModule(context: Context, accountId: AccountId, prefix: String = "") extends StorageModule(context, accountId, prefix) {
    override lazy val userPrefs: UserPreferences = new TestUserPreferences
  }

  class MockedUserModule(userId: UserId, account: AccountService) extends UserModule(userId, account) {
    override lazy val otrClient: OtrClient = new MockedOtrClient(account.netClient)
  }

  class MockedZMessaging(clientId: ClientId, userModule: UserModule) extends ZMessaging(clientId, userModule) {

    override lazy val flowmanager: DefaultFlowManagerService = new MockedFlowManagerService(context, zNetClient, websocket, prefs, network)
    override lazy val mediamanager: DefaultMediaManagerService = new MockedMediaManagerService(context, prefs)

    override lazy val assetClient        = new AssetClientImpl(zNetClient) {
      override def postImageAssetData(asset: AssetData, data: LocalData, nativePush: Boolean = true, convId: RConvId): ErrorOrResponse[RAssetId] = suite.postImageAssetData(asset, convId, data, nativePush)
    }

    override lazy val usersClient        = new MockedUsersClient(zNetClient)
    override lazy val convClient         = new ConversationsClient(zNetClient) {
      override def loadConversations(start: Option[RConvId], limit: Int): ErrorOrResponse[ConversationsResult] = suite.loadConversations(start, limit)
      override def loadConversations(ids: Seq[RConvId]): ErrorOrResponse[Seq[ConversationResponse]] = suite.loadConversations(ids)
      override def postMemberJoin(conv: RConvId, members: Seq[UserId]): ErrorOrResponse[Option[MemberJoinEvent]] = suite.postMemberJoin(conv, members)
      override def postMemberLeave(conv: RConvId, member: UserId): ErrorOrResponse[Option[MemberLeaveEvent]] = suite.postMemberLeave(conv, member)
      override def postConversation(users: Seq[UserId], name: Option[String]): ErrorOrResponse[ConversationResponse] = suite.postConversation(users, name)
      override def postConversationState(convId: RConvId, state: ConversationState): ErrorOrResponse[Boolean] = suite.postConversationState(convId, state)
      override def postName(convId: RConvId, name: String): ErrorOrResponse[Option[RenameConversationEvent]] = suite.postName(convId, name)
    }
    override lazy val messagesClient     = new MessagesClient(zNetClient) {
      override def postMessage(conv: RConvId, content: OtrMessage, ignoreMissing: Boolean, recipients: Option[Set[UserId]]): ErrorOrResponse[MessageResponse] = suite.postMessage(conv, content, ignoreMissing)
    }
    override lazy val eventsClient       = new EventsClient(zNetClient) {
      override def loadNotifications(since: Option[Uid], client: ClientId, pageSize: Int): ErrorOrResponse[Option[Uid]] = suite.loadNotifications(since, client, pageSize)
      override def loadLastNotification(client: ClientId): ErrorOrResponse[Option[PushNotification]] = suite.loadLastNotification()
    }
    override lazy val voiceClient        = new VoiceChannelClient(zNetClient) {
      override def loadCallState(id: RConvId): ErrorOrResponse[CallStateEvent] = suite.loadCallState(id)
      override def updateSelfCallState(id: RConvId, deviceState: CallDeviceState, cause: CauseForCallStateEvent = CauseForCallStateEvent.REQUESTED): ErrorOrResponse[Either[JoinCallFailed, CallStateEvent]] = suite.updateSelfCallState(id, deviceState, cause)
    }
    override lazy val abClient           = new AddressBookClient(zNetClient) {
      override def postAddressBook(a: AddressBook): ErrorOrResponse[Seq[UserAndContactIds]] = suite.postAddressBook(a)
    }
    override lazy val gcmClient          = new PushTokenClient(zNetClient) {}
    override lazy val typingClient       = new TypingClient(zNetClient) {
      override def updateTypingState(id: RConvId, isTyping: Boolean): ErrorOrResponse[Unit] = suite.updateTypingState(id, isTyping)
    }
    override lazy val invitationClient   = new InvitationClient(zNetClient) {
      override def postInvitation(i: Invitation): ErrorOrResponse[Either[UserId, ConfirmedInvitation]] = suite.postInvitation(i)
    }
    override lazy val giphyClient        = new GiphyClient(zNetClient) {}
    override lazy val userSearchClient   = new UserSearchClient(zNetClient) {
      override def graphSearch(query: SearchQuery, limit: Int) = suite.graphSearch(query, limit)
    }
    override lazy val connectionsClient  = new ConnectionsClient(zNetClient) {
      override def loadConnections(start: Option[UserId], pageSize: Int): ErrorOrResponse[Seq[UserConnectionEvent]] = suite.loadConnections(start, pageSize)
      override def loadConnection(other: UserId): ErrorOrResponse[UserConnectionEvent] = suite.loadConnection(other)
      override def updateConnection(user: UserId, status: ConnectionStatus): ErrorOrResponse[Option[UserConnectionEvent]] = suite.updateConnection(user, status)
    }

    override lazy val websocket: service.push.WebSocketClientService = new service.push.WebSocketClientService(context, lifecycle, zNetClient, network, global.backend, clientId, timeouts, pushToken) {

      override def createWebSocketClient(clientId: ClientId): WebSocketClient = new WebSocketClient(context, zNetClient.client, Uri.parse(backend.websocketUrl), zNetClient.auth) {
        override def close() = dispatcher {
          connected ! false
          if (suite.pushService.contains(push)) suite.pushService = None
        }
        override protected def connect() = dispatcher {
          suite.pushService = Some(push)
          connected ! true
          null
        }
      }
    }

    override lazy val timeouts = suite.timeouts
  }

  class MockedUsersClient(client: ZNetClient) extends UsersClient(client) {
    override def loadUsers(ids: Seq[UserId]): ErrorOrResponse[IndexedSeq[UserInfo]] = suite.loadUsers(ids)
    override def loadSelf(): ErrorOrResponse[UserInfo] = suite.loadSelf()
    override def updateSelf(info: UserInfo): ErrorOrResponse[Unit] = suite.updateSelf(info)
  }

  class MockedCredentialsUpdateClient(client: ZNetClient) extends CredentialsUpdateClient(client) {
    override def updatePassword(newPassword: String, currentPassword: Option[String]): ErrorOrResponse[Unit] = suite.updatePassword(newPassword, currentPassword)
    override def updateEmail(email: EmailAddress): ErrorOrResponse[Unit] = suite.updateEmail(email)
    override def updatePhone(phone: PhoneNumber): ErrorOrResponse[Unit] = suite.updatePhone(phone)
  }

  class MockedOtrClient(client: ZNetClient) extends OtrClient(client) {
    override def loadClients(): ErrorOrResponse[Seq[Client]] = suite.loadOtrClients()
    override def postClient(userId: AccountId, client: Client, lastKey: PreKey, keys: Seq[PreKey], password: Option[String]): ErrorOrResponse[Client] = suite.postOtrClient(userId, client, lastKey, keys, password)
    override def loadPreKeys(users: Map[UserId, Seq[ClientId]]): ErrorOrResponse[Map[UserId, Seq[ClientKey]]] = suite.loadPreKeys(users)
    override def loadRemainingPreKeys(id: ClientId): ErrorOrResponse[Seq[Int]] = suite.loadRemainingPreKeys(id)
    override def updateKeys(id: ClientId, prekeys: Seq[PreKey], lastKey: Option[PreKey] = None, sigKey: Option[SignalingKey] = None): ErrorOrResponse[Unit] = suite.updateKeys(id, prekeys, lastKey, sigKey)
  }

  class MockedZMessagingFactory(global: GlobalModule) extends ZMessagingFactory(global) {

    override def client(credentials: CredentialsHandler): ZNetClient = new EmptyClient

    override def usersClient(client: ZNetClient): UsersClient = new MockedUsersClient(client)

    override def credentialsClient(netClient: ZNetClient): CredentialsUpdateClient = new MockedCredentialsUpdateClient(netClient)

    override def baseStorage(accountId: AccountId): StorageModule = new StorageModule(global.context, accountId, Random.nextInt.toHexString)

    override def userModule(userId: UserId, account: AccountService) = new MockedUserModule(userId, account)

    override def zmessaging(clientId: ClientId, userModule: UserModule): ZMessaging = new MockedZMessaging(clientId, userModule)

  }

  override def push(n: PushNotification): Boolean = pushService match {
    case Some(service) => service.onPushNotification(n); true
    case None =>
      verbose(s"push ignored, web socket not connected")
      false
  }
  override def pushGcm(notification: PushNotification, userId: UserId) =
    Option(ZMessaging.currentAccounts) foreach { accounts =>
      accounts.getCurrentZms.foreach {
        case Some(zms) if zms.selfUserId == userId => zms.push.cloudPushNotificationsToProcess.mutate(_ + notification.id)
        case _ =>
      }(Threading.Background)
    }

  class MockedGlobalModule(context: Context, backend: BackendConfig, testClient: AsyncClient) extends GlobalModule(context, testBackend) {
    override lazy val client: AsyncClient = testClient
    override lazy val clientWrapper: Future[ClientWrapper] = TestClientWrapper()
    override lazy val loginClient: LoginClient = new LoginClient(client, backend) {
      override def login(user: AccountId, credentials: Credentials): CancellableFuture[LoginResult] = suite.login(user, credentials)
      override def access(cookie: Cookie, token: Option[Token]): CancellableFuture[LoginResult] = suite.access(cookie, token)

    }
    override lazy val regClient: RegistrationClient = new RegistrationClient(client, backend) {
      override def register(user: AccountId, credentials: Credentials, name: String, accentId: Option[Int]) = suite.register(user, credentials, name, accentId)
      override def requestPhoneConfirmationCode(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] = suite.requestPhoneConfirmationCode(phone, kindOfAccess)
      override def requestPhoneConfirmationCall(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] = suite.requestPhoneConfirmationCall(phone, kindOfAccess)

      override def verifyPhoneNumber(credentials: PhoneCredentials, kindOfVerification: KindOfVerification): ErrorOrResponse[Unit] = suite.verifyPhoneNumber(credentials, kindOfVerification)
    }
    override lazy val blacklistClient    = new VersionBlacklistClient(globalClient, testBackend) {
      override def loadVersionBlacklist(): ErrorOrResponse[VersionBlacklist] = suite.loadVersionBlacklist()
    }
    override lazy val factory: ZMessagingFactory = new MockedZMessagingFactory(this)
  }

  override lazy val testClient: AsyncClient = new EmptyAsyncClient

  override lazy val globalModule = new MockedGlobalModule(context, testBackend, testClient)


}

trait MockedFlows { test: ApiSpec =>
  private lazy val mocked = zmessaging.flowmanager.asInstanceOf[MockedFlowManagerService]

  def establishMedia(conv: RConvId): Unit = zmessaging.flowmanager.onMediaEstablished ! conv
  def changeVolume(conv: RConvId, participant: UserId, volume: Float): Unit = zmessaging.flowmanager.onVolumeChanged ! (conv, participant, volume)
  def flowManagerError(conv: RConvId, errorCode: Int): Unit = zmessaging.flowmanager.onFlowManagerError ! (conv, errorCode)
  def sessionIdUsedToAcquireFlows: Option[CallSessionId] = mocked.sessionIdUsedToAcquireFlows
  def setCanSendVideo(conv: RConvId, canSend: Boolean): Unit = if (canSend) mocked.convsThatCanSendVideo += conv else mocked.convsThatCanSendVideo -= conv
  def hasConvVideoSendState(conv: RConvId, state: VideoSendState): Boolean = mocked.convsThatSendVideo.get(conv).contains(state)
  def currentVideoCaptureDeviceId(conv: RConvId): Option[String] = mocked.videoCaptureDeviceId.get(conv)

  def resetVideoCalls(): Unit = {
    mocked.convsThatSendVideo = Map.empty
    mocked.convsThatCanSendVideo = Set.empty
  }
}

trait MockedMedia { test: ApiSpec =>
  private lazy val mocked = zmessaging.mediamanager.asInstanceOf[MockedMediaManagerService]

  def changePlaybackRoute(route: PlaybackRoute): Unit = mocked.changePlaybackRoute(route)
}

trait MockedWebSocket {
  def push(n: PushNotification): Boolean
}

trait MockedGcm {
  def pushGcm(notification: PushNotification, userId: UserId): Unit
}

trait MockedClient { test: ApiSpec =>

  def login(user: AccountId, credentials: Credentials): CancellableFuture[LoginResult] = successful[LoginResult](Right((Token("token", "type", Long.MaxValue), Some(Cookie("cookie")))))
  def access(cookie: Cookie, token: Option[Token]): CancellableFuture[LoginResult] = successful[LoginResult](Right((Token("token", "type", Long.MaxValue), Some(Cookie("cookie")))))
  def register(user: AccountId, credentials: Credentials, name: String, accentId: Option[Int]): ErrorOrResponse[(UserInfo, Option[Cookie])] =
    successful(Right((UserInfo(UserId(), Some(name), accentId, credentials.maybeEmail, credentials.maybePhone, None), Some(Cookie("cookie")))))
  def requestPhoneConfirmationCode(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] = successful(ActivateResult.Success)
  def requestPhoneConfirmationCall(phone: PhoneNumber, kindOfAccess: KindOfAccess): CancellableFuture[ActivateResult] = successful(ActivateResult.Success)
  def verifyPhoneNumber(credentials: PhoneCredentials, kindOfVerification: KindOfVerification): ErrorOrResponse[Unit] = successful(Right(()))

  def updateEmail(email: EmailAddress): ErrorOrResponse[Unit] = successful(Right(()))
  def updatePhone(phone: PhoneNumber): ErrorOrResponse[Unit] = successful(Right(()))
  def updatePassword(newPassword: String, currentPassword: Option[String]): ErrorOrResponse[Unit] = successful(Right(()))

  def loadVersionBlacklist(): ErrorOrResponse[VersionBlacklist] = successful(Right(VersionBlacklist()))
  def loadLastNotification(): ErrorOrResponse[Option[PushNotification]] = successful(Right(None))
  def loadNotifications(since: Option[Uid], client: ClientId, pageSize: Int, isFirstPage: Boolean = true): ErrorOrResponse[Option[Uid]] = CancellableFuture.successful(Right(since))
  def postAddressBook(a: AddressBook): ErrorOrResponse[Seq[UserAndContactIds]] = successful(Right(Nil))
  def postInvitation(i: Invitation): ErrorOrResponse[Either[UserId, ConfirmedInvitation]] = successful(Left(ErrorResponse.internalError("not implemented")))
  def loadConnections(start: Option[UserId], pageSize: Int): ErrorOrResponse[Seq[UserConnectionEvent]] = successful(Right(Nil))
  def loadConnection(other: UserId): ErrorOrResponse[UserConnectionEvent] = successful(Left(ErrorResponse.internalError("not implemented")))
  def loadConversations(start: Option[RConvId], limit: Int): ErrorOrResponse[ConversationsResult] = successful(Right(ConversationsResult(Nil, hasMore = false)))
  def loadConversations(ids: Seq[RConvId]): ErrorOrResponse[Seq[ConversationResponse]] = successful(Left(ErrorResponse.internalError("not implemented")))
  def loadUsers(ids: Seq[UserId]): ErrorOrResponse[IndexedSeq[UserInfo]] = successful(Right(IndexedSeq()))
  def loadSelf(): ErrorOrResponse[UserInfo] = successful(Left(ErrorResponse.Cancelled))
  def loadCallState(id: RConvId): ErrorOrResponse[CallStateEvent] = successful(Right(CallStateEvent(id, Some(Set.empty), cause = CauseForCallStateEvent.REQUESTED)))
  def updateSelfCallState(id: RConvId, deviceState: CallDeviceState, cause: CauseForCallStateEvent): ErrorOrResponse[Either[JoinCallFailed, CallStateEvent]] = successful(Right(Right(CallStateEvent(id, Some(Set.empty), cause = cause))))
  def graphSearch(query: SearchQuery, limit: Int): ErrorOrResponse[Seq[UserSearchEntry]] = successful(Right(Seq.empty))
  def postMemberJoin(conv: RConvId, members: Seq[UserId]): ErrorOrResponse[Option[MemberJoinEvent]] = successful(Left(ErrorResponse.internalError("not implemented")))
  def postMemberLeave(conv: RConvId, member: UserId): ErrorOrResponse[Option[MemberLeaveEvent]] = successful(Left(ErrorResponse.internalError("not implemented")))
  def loadCommonConnections(id: UserId): ErrorOrResponse[Seq[UserSearchEntry]] = successful(Right(Seq.empty))
  def postConversation(users: Seq[UserId], name: Option[String]): ErrorOrResponse[ConversationResponse] = successful(Left(ErrorResponse.internalError("not implemented")))
  def postConversationState(convId: RConvId, state: ConversationState): ErrorOrResponse[Boolean] = successful(Right(true))
  def postName(convId: RConvId, name: String): ErrorOrResponse[Option[RenameConversationEvent]] = successful(Left(ErrorResponse.internalError("not implemented")))
  def updateConnection(user: UserId, status: ConnectionStatus): ErrorOrResponse[Option[UserConnectionEvent]] = successful(Right(None))
  def postImageAssetData(asset: AssetData, convId: RConvId, data: LocalData, nativePush: Boolean): ErrorOrResponse[RAssetId] = successful(Left(ErrorResponse.internalError("not implemented")))
  def updateSelf(info: UserInfo): ErrorOrResponse[Unit] = successful(Right(()))
  def postMessage(convId: RConvId, msg: OtrMessage, ignoreMissing: Boolean): ErrorOrResponse[MessageResponse] = successful(Left(ErrorResponse.internalError("not implemented")))
  def loadOtrClients(): ErrorOrResponse[Seq[Client]] = successful(Right(Seq.empty))
  def postOtrClient(userId: AccountId, client: Client, lastKey: PreKey, keys: Seq[PreKey], password: Option[String]): ErrorOrResponse[Client] = successful(Right(client))
  def loadPreKeys(users: Map[UserId, Seq[ClientId]]): ErrorOrResponse[Map[UserId, Seq[ClientKey]]] = successful(Right(Map.empty))
  def loadRemainingPreKeys(id: ClientId): ErrorOrResponse[Seq[Int]] = successful(Right(Nil))
  def updateKeys(id: ClientId, prekeys: Seq[PreKey], lastKey: Option[PreKey], sigKey: Option[SignalingKey]): ErrorOrResponse[Unit] = successful(Right(()))
  def updateTypingState(id: RConvId, isTyping: Boolean): ErrorOrResponse[Unit] = successful(Right(()))
}
