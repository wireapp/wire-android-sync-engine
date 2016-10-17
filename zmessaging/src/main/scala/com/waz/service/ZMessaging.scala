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

import android.content.Context
import com.softwaremill.macwire._
import com.waz.{Analytics, HockeyApp}
import com.waz.ZLog._
import com.waz.api.NotificationsHandler.NotificationsHandlerFactory
import com.waz.api._
import com.waz.api.impl.LogLevel
import com.waz.content.{MembersStorage, UsersStorage, ZmsDatabase, _}
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.EventScheduler.{Interleaved, Parallel, Sequential, Stage}
import com.waz.service.assets.{AssetLoader, AssetService, RecordAndPlayService}
import com.waz.service.call._
import com.waz.service.conversation._
import com.waz.service.downloads.AssetDownloader
import com.waz.service.images.{ImageAssetGenerator, ImageLoader}
import com.waz.service.invitations.InvitationService
import com.waz.service.media._
import com.waz.service.messages._
import com.waz.service.otr._
import com.waz.service.push._
import com.waz.service.tracking.{TrackingEventsService, TrackingService}
import com.waz.sync.client._
import com.waz.sync.handler._
import com.waz.sync.otr.OtrSyncHandler
import com.waz.threading.{DispatchQueueStats, SerialDispatchQueue, Threading}
import com.waz.ui.UiModule
import com.waz.utils.Locales
import com.waz.utils.events.EventContext
import com.waz.znet.{CredentialsHandler, _}
import net.hockeyapp.android.Constants

import scala.concurrent.Future

class ZMessagingFactory(global: GlobalModule) {

  def baseStorage(accountId: AccountId) = new StorageModule(global.context, accountId, "")

  def client(credentials: CredentialsHandler) = new ZNetClient(credentials, global.client, global.backend, global.loginClient)

  def usersClient(client: ZNetClient) = new UsersClient(client)

  def credentialsClient(netClient: ZNetClient) = new CredentialsUpdateClient(netClient)

  def cryptobox(accountId: AccountId, storage: StorageModule) = new CryptoBoxService(global.context, accountId, global.metadata, storage.kvStorage)

  def userModule(userId: UserId, account: AccountService) = wire[UserModule]

  def zmessaging(clientId: ClientId, userModule: UserModule) = wire[ZMessaging]
}


class StorageModule(context: Context, accountId: AccountId, dbPrefix: String) {
  lazy val db                = new ZmsDatabase(accountId, context, dbPrefix)
  lazy val kvStorage         = wire[KeyValueStorage]
  lazy val usersStorage      = wire[UsersStorage]
  lazy val otrClientsStorage = wire[OtrClientsStorage]
  lazy val membersStorage    = wire[MembersStorage]
  lazy val assetsStorage     = wire[AssetsStorage]
  lazy val voiceStorage      = wire[VoiceChannelStorage]
  lazy val reactionsStorage  = wire[ReactionsStorage]
  lazy val notifStorage      = wire[NotificationStorage]
  lazy val convsStorage      = wire[ConversationStorage]
  lazy val msgDeletions      = wire[MsgDeletionStorage]
  lazy val searchQueryCache  = wire[SearchQueryCacheStorage]
  lazy val commonConnections = wire[CommonConnectionsStorage]
  lazy val msgEdits          = wire[EditHistoryStorage]
}


class ZMessaging(val clientId: ClientId, val userModule: UserModule) {

  private implicit val logTag: LogTag = logTagFor[ZMessaging]
  private implicit val dispatcher = new SerialDispatchQueue(name = "ZMessaging")
  private implicit val ev = EventContext.Global

  val account    = userModule.account
  val global     = account.global

  val selfUserId = userModule.userId
  val accountId  = account.id

  val zNetClient = account.netClient
  val storage    = account.storage
  val lifecycle  = account.lifecycle

  lazy val cryptoBox            = account.cryptoBox
  lazy val sync                 = userModule.sync
  lazy val syncHandler          = userModule.syncHandler
  lazy val otrClientsService    = userModule.clientsService
  lazy val syncRequests         = userModule.syncRequests
  lazy val otrClientsSync       = userModule.clientsSync
  lazy val verificationUpdater  = userModule.verificationUpdater

  def context           = global.context
  def imageCache        = global.imageCache
  def permissions       = global.permissions
  def phoneNumbers      = global.phoneNumbers
  def prefs             = global.prefs
  def downloader        = global.downloader
  def bitmapDecoder     = global.bitmapDecoder
  def gcmGlobal         = global.gcmGlobal
  def timeouts          = global.timeouts
  def cache             = global.cache
  def mediamanager      = global.mediaManager
  def globalRecAndPlay  = global.recordingAndPlayback
  def tempFiles         = global.tempFiles
  def metadata          = global.metadata
  def network           = global.network
  def handlerFactory    = global.handlerFactory
  def blacklist         = global.blacklist
  def backend           = global.backend
  def accountsStorage   = global.accountsStorage
  def streamLoader      = global.streamLoader
  def videoLoader       = global.videoLoader
  def pcmAudioLoader    = global.pcmAudioLoader

  def db                = storage.db
  def kvStorage         = storage.kvStorage
  def usersStorage      = storage.usersStorage
  def otrClientsStorage = storage.otrClientsStorage
  def membersStorage    = storage.membersStorage
  def assetsStorage     = storage.assetsStorage
  def voiceStorage      = storage.voiceStorage
  def reactionsStorage  = storage.reactionsStorage
  def notifStorage      = storage.notifStorage
  def convsStorage      = storage.convsStorage
  def msgDeletions      = storage.msgDeletions
  def msgEdits          = storage.msgEdits
  def searchQueryCache  = storage.searchQueryCache
  def commonConnections = storage.commonConnections

  def gcmState          = gcm.gcmState

  lazy val messagesStorage: MessagesStorage = wire[MessagesStorage]
  lazy val msgAndLikes: MessageAndLikesStorage = wire[MessageAndLikesStorage]

  lazy val spotifyClientId  = metadata.spotifyClientId

  lazy val youtubeClient      = wire[YouTubeClient]
  lazy val soundCloudClient   = wire[SoundCloudClient]
  lazy val spotifyClient      = wire[SpotifyClient]
  lazy val assetClient        = wire[AssetClient]
  lazy val usersClient        = wire[UsersClient]
  lazy val convClient         = wire[ConversationsClient]
  lazy val eventsClient       = wire[EventsClient]
  lazy val voiceClient        = wire[VoiceChannelClient]
  lazy val abClient           = wire[AddressBookClient]
  lazy val gcmClient          = wire[GcmClient]
  lazy val typingClient       = wire[TypingClient]
  lazy val invitationClient   = wire[InvitationClient]
  lazy val giphyClient        = wire[GiphyClient]
  lazy val userSearchClient   = wire[UserSearchClient]
  lazy val connectionsClient  = wire[ConnectionsClient]
  lazy val messagesClient     = wire[MessagesClient]
  lazy val openGraphClient    = wire[OpenGraphClient]
  lazy val otrClient          = wire[com.waz.sync.client.OtrClient]


  lazy val convsContent: ConversationsContentUpdater = wire[ConversationsContentUpdater]
  lazy val messagesContent: MessagesContentUpdater = wire[MessagesContentUpdater]

  lazy val assetDownloader = wire[AssetDownloader]
  lazy val assetLoader     = wire[AssetLoader]
  lazy val imageLoader     = wire[ImageLoader]

  lazy val pushSignals                           = wire[PushServiceSignals]
  lazy val push: PushService                     = wire[PushService]
  lazy val gcm: GcmService                       = wire[GcmService]
  lazy val errors                                = wire[ErrorsService]
  lazy val reporting                             = new ZmsReportingService(accountId, global.reporting)
  lazy val websocket: WebSocketClientService     = wire[WebSocketClientService]
  lazy val userSearch                            = wire[UserSearchService]
  lazy val assetGenerator                        = wire[ImageAssetGenerator]
  lazy val assetMetaData                         = wire[com.waz.service.assets.MetaDataService]
  lazy val assetPreview                          = wire[com.waz.service.assets.PreviewService]
  lazy val assets: AssetService                  = wire[AssetService]
  lazy val users: UserService                    = wire[UserService]
  lazy val conversations: ConversationsService   = wire[ConversationsService]
  lazy val convsNotifier                         = wire[ConversationsNotifier]
  lazy val convEvents: ConversationEventsService = wire[ConversationEventsService]
  lazy val convsUi                               = wire[ConversationsUiService]
  lazy val convsStats                            = wire[ConversationsListStateService]
  lazy val messages: MessagesService             = wire[MessagesService]
  lazy val connection: ConnectionService         = wire[ConnectionService]
  lazy val flowmanager: FlowManagerService       = wire[FlowManagerService]
  lazy val voiceContent                          = wire[VoiceChannelContent]
  lazy val voice: VoiceChannelService            = wire[VoiceChannelService]
  lazy val contacts: ContactsService             = wire[ContactsService]
  lazy val typing: TypingService                 = wire[TypingService]
  lazy val invitations                           = wire[InvitationService]
  lazy val richmedia                             = wire[RichMediaService]
  lazy val tracking                              = wire[TrackingService]
  lazy val trackingEvents                        = wire[TrackingEventsService]
  lazy val giphy                                 = wire[GiphyService]
  lazy val youtubeMedia                          = wire[YouTubeMediaService]
  lazy val soundCloudMedia                       = wire[SoundCloudMediaService]
  lazy val spotifyMedia                          = wire[SpotifyMediaService]
  lazy val otrService: OtrService                = wire[OtrService]
  lazy val genericMsgs: GenericMessageService    = wire[GenericMessageService]
  lazy val reactions: ReactionsService           = wire[ReactionsService]
  lazy val notifications: NotificationService    = wire[NotificationService]
  lazy val callLog                               = wire[CallLogService]
  lazy val recordAndPlay                         = wire[RecordAndPlayService]
  lazy val receipts                              = wire[ReceiptService]
  lazy val ephemeral                             = wire[EphemeralMessagesService]

  lazy val assetSync        = wire[AssetSyncHandler]
  lazy val usersearchSync   = wire[UserSearchSyncHandler]
  lazy val usersSync        = wire[UsersSyncHandler]
  lazy val conversationSync = wire[ConversationsSyncHandler]
  lazy val connectionsSync  = wire[ConnectionsSyncHandler]
  lazy val voicechannelSync = wire[VoiceChannelSyncHandler]
  lazy val addressbookSync  = wire[AddressBookSyncHandler]
  lazy val gcmSync          = wire[GcmSyncHandler]
  lazy val typingSync       = wire[TypingSyncHandler]
  lazy val richmediaSync    = wire[RichMediaSyncHandler]
  lazy val invitationSync   = wire[InvitationSyncHandler]
  lazy val messagesSync     = wire[MessagesSyncHandler]
  lazy val otrSync          = wire[OtrSyncHandler]
  lazy val reactionsSync    = wire[ReactionsSyncHandler]
  lazy val lastReadSync     = wire[LastReadSyncHandler]
  lazy val clearedSync      = wire[ClearedSyncHandler]
  lazy val openGraphSync    = wire[OpenGraphSyncHandler]

  lazy val eventPipeline = new EventPipeline(Vector(otrService.eventTransformer), eventScheduler.enqueue)

  lazy val eventScheduler = {

    new EventScheduler(
      Stage(Sequential)(
        Stage(Interleaved)(
          connection.connectionEventsStage,
          connection.contactJoinEventsStage,
          users.userUpdateEventsStage,
          users.userDeleteEventsStage,
          flowmanager.callEventsStage,
          voice.callStateEventsStage,
          voice.memberLeaveEventsStage,
          conversations.convStateEventProcessingStage,
          typing.typingEventStage,
          otrClientsService.otrClientsProcessingStage,
          gcm.eventProcessingStage,
          Stage(Parallel)(
            UnarchivingEventProcessingStage(users, convsStorage),
            convEvents.conversationEventsStage,
            Stage(Interleaved)(
              messages.messageEventProcessingStage,
              genericMsgs.eventProcessingStage
            )
          )
        ),
        notifications.notificationEventsStage,
        notifications.lastReadProcessingStage
      )
    )
  }

  // force loading of services which should run on start
  {
    conversations
    users

    push // connect on start

    // services listening on lifecycle verified login events
    contacts
    syncRequests

    // services listening for storage updates
    richmedia
    ephemeral

    tempFiles
    recordAndPlay

    reporting.addStateReporter { pw =>
      Future {
        kvStorage foreachCached {
          case KeyValueData(k, v) => pw.println(s"$k: $v")
        }
      }
    }
  }
}

object ZMessaging { self =>
  private implicit val logTag: LogTag = logTagFor(ZMessaging)

  require(LogLevel.initialized)

  Threading.AssertsEnabled = ZmsVersion.DEBUG
  DispatchQueueStats.Debug = ZmsVersion.DEBUG
  Analytics.instance = HockeyApp

  private[waz] var context: Context = _

  private var backend = BackendConfig.DevBackend

  def useBackend(conf: BackendConfig) = {
    assert(context == null, "ZMessaging.useBackend should only be called before any ZMessagingApi instance is created, do that only once, asap in Application.onCreate")
    backend = conf
  }
  def useEdgeBackend(): Unit = useBackend(BackendConfig.EdgeBackend)
  def useProdBackend(): Unit = useBackend(BackendConfig.ProdBackend)

  private lazy val global: GlobalModule = new GlobalModule(context, backend)
  private lazy val accounts: Accounts = new Accounts(global)
  private lazy val ui: UiModule = new UiModule(accounts)

  // mutable for testing FIXME: get rid of that
  private [waz] var currentUi: UiModule = _
  private [waz] var currentGlobal: GlobalModule = _
  private [waz] var currentAccounts: Accounts = _

  def onCreate(context: Context) = {
    Threading.assertUiThread()

    if (this.currentUi == null) {
      this.context = context.getApplicationContext
      Constants.loadFromContext(context)
      currentUi = ui
      currentGlobal = global
      currentAccounts = accounts
      Threading.Background { Locales.preloadTransliterator() } // "preload"... - this should be very fast, normally, but slows down to 10 to 20 seconds when multidexed...
    }
  }

  def notificationsHandlerFactory(context: Context) = context.getApplicationContext match { // TODO: use some registration mechanism instead of expecting the app to implement this interface
    case app: NotificationsHandlerFactory => app
    case app =>
      error(s"Application: '$app' doesn't implement NotificationsHandlerFactory")
      new NotificationsHandlerFactory {
        override def getCallingEventsHandler: CallingEventsHandler = EmptyEventsHandler
        override def getTrackingEventsHandler: TrackingEventsHandler = EmptyTrackingEventsHandler
      }
  }

  object EmptyEventsHandler extends CallingEventsHandler {
    override def onCallingEvent(event: CallingEvent): Unit = ()
  }

  object EmptyTrackingEventsHandler extends TrackingEventsHandler {
    override def onTrackingEvent(event: TrackingEvent): Unit = ()
    override def onAvsMetricsEvent(avsMetrics: AvsMetrics): Unit = ()
  }
}
