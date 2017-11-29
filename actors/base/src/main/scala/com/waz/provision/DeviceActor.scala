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
package com.waz.provision

import java.io.{ByteArrayInputStream, File, FileInputStream}
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.SupervisorStrategy._
import akka.actor._
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waz.api.OtrClient.DeleteCallback
import com.waz.api._
import com.waz.api.impl.{DoNothingAndProceed, ErrorResponse, ZMessagingApi}
import com.waz.content.Preferences.PrefKey
import com.waz.content.{Database, GlobalDatabase}
import com.waz.model.ConversationData.ConversationType
import com.waz.model.UserData.ConnectionStatus
import com.waz.model.otr.ClientId
import com.waz.model.{ConvId, ConversationData, Liking, RConvId, MessageContent => _, _}
import com.waz.service._
import com.waz.service.otr.CryptoBoxService
import com.waz.testutils.Implicits.{CoreListAsScala, _}
import com.waz.threading.{CancellableFuture, DispatchQueueStats, Threading}
import com.waz.ui.UiModule
import com.waz.utils.RichFuture.traverseSequential
import com.waz.utils._
import com.waz.znet.ClientWrapper
import org.threeten.bp.Instant

import scala.concurrent.Future.successful
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Random

object DeviceActor {
  def props(deviceName: String,
            application: Context,
            backend: BackendConfig = BackendConfig.StagingBackend,
            wrapper: Future[ClientWrapper]) =
  Props(new DeviceActor(deviceName, application, backend, wrapper)).withDispatcher("ui-dispatcher")
}

class DeviceActor(val deviceName: String,
                  val application: Context,
                  backend: BackendConfig = BackendConfig.StagingBackend,
                  wrapper: Future[ClientWrapper]) extends Actor with ActorLogging {

  import ActorMessage._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 10.seconds) {
      case exc: Exception =>
        log.error(exc, s"device actor '$deviceName' died")
        Stop
    }
  lazy val delayNextAssetPosting = new AtomicBoolean(false)

  lazy val globalModule = new GlobalModuleImpl(application, backend) { global =>
    ZMessaging.currentGlobal = this
    override lazy val storage: Database = new GlobalDatabase(application, Random.nextInt().toHexString)
    override lazy val clientWrapper: Future[ClientWrapper] = wrapper

    override lazy val metadata: MetaDataService = new MetaDataService(context) {
      override val cryptoBoxDirName: String = "otr_" + Random.nextInt().toHexString
      override lazy val deviceModel: String = deviceName
      override lazy val localBluetoothName: String = deviceName
    }

    override lazy val factory: ZMessagingFactory = new ZMessagingFactory(this) {
      override def zmessaging(teamId: Option[TeamId], clientId: ClientId, user: UserModule, st: StorageModule, cb: CryptoBoxService): ZMessaging =
        new ZMessaging(teamId, clientId, user, st, cb) {

        }
    }
  }

  lazy val instance = new AccountsServiceImpl(globalModule) {
    override val activeAccountPref = global.prefs.preference(PrefKey[Option[AccountId]]("current_user_" + Random.nextInt().toHexString))
  }
  lazy val ui = new UiModule(instance)
  lazy val api = {
    val api = new ZMessagingApi()(ui)
    api.onCreate(application)
    api.onResume()
    api
  }
  def optZms = Await.result(api.ui.getCurrent, 5.seconds)
  lazy val zmessaging = optZms.get
  lazy val prefs = zmessaging.prefs
  lazy val convs = api.getConversations
  lazy val archived = convs.getArchivedConversations

  //Using a large value so that the test processes will always timeout first, and not the remotes
  implicit val defaultTimeout = 5.minutes
  implicit val execContext = context.dispatcher.prepare()

  implicit def zmsDb: SQLiteDatabase = api.account.get.storage.currentValue("actors").get.db.dbHelper.getWritableDatabase

  val maxConnectionAttempts = 10

  def searchConvsListById(remoteId: RConvId): IConversation => Boolean = { conv =>
    conv.data.remoteId.str == remoteId.str || conv.data.id.str == remoteId.str
  }

  def searchConvsListByName(name: String): IConversation => Boolean = _.name == name

  def findConvById(remoteId: RConvId): IConversation = convs.find(searchConvsListById(remoteId)).orElse(archived.find(searchConvsListById(remoteId))).get
  def convExistsById(remoteId: RConvId): Boolean = convs.exists(searchConvsListById(remoteId)) || archived.exists(searchConvsListById(remoteId))
  def convExistsByName(name: String): Boolean = convs.exists(searchConvsListByName(name)) || archived.exists(searchConvsListByName(name))
  def findConvByName(name: String): IConversation = convs.find(searchConvsListByName(name)).orElse(archived.find(searchConvsListByName(name))).get

  override def receive = preProcess(active)

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    api.onPause()
    api.onDestroy()
    Await.result(api.ui.getCurrent, 5.seconds) foreach { zms =>
      zms.syncContent.syncStorage { storage =>
        storage.getJobs foreach { job => storage.remove(job.id) }
      }
    }
    super.postStop()
  }

  /**
   * Intercepts all messages to perform common logic like logging, and then passes
   * the message onto the specified handleFunction
    *
    * @param handleFunction the partial function to carry out the actual message processing after pre-processing
   * @return a Receive partial function
   */
  def preProcess(handleFunction: Receive): Receive = {
    case message@_ =>
      log.info(s"Received message: $message")
      handleFunction(message)
  }

  /////////////////////////////////////////////////////////////////////
  /// Messages that respond immediately or handle their own delays
  /////////////////////////////////////////////////////////////////////
  def active: Receive = activeFuture orElse {
    case Echo(msg, _) =>
      sender ! Echo(msg, deviceName)

    case RegisterPhone(phone, code, name, color) =>

    case Login(email, pass) =>
      val senderRef = sender()
      if (api.getSelf.getUser != null) {
        sender ! Failed(s"Process is already logged in as user: ${api.getSelf.getEmail}")
      }
      ZMessaging.currentAccounts.loginEmail(EmailAddress(email), pass).map {
        case Right(()) =>
          senderRef ! Successful
        case Left(ErrorResponse(code, message, label)) =>
          log.info(s"Failed login: $code, $message, $label")
          senderRef ! Failed(s"Failed login: $code, $message, $label")
      }

    case SendRequest(userId) =>
      val senderRef = sender()
      Option(userId) match {
        case Some(_) =>
          api.getUser(userId.str).connect("meep")
          waitUntil(convs) { _.asScala.exists(_.getType == ConversationType.WaitForConnection) } onSuccess {
            case _ => senderRef ! Successful
          }
        case None =>
          senderRef ! Failed("UserId cannot be null")
      }

    case message@_ =>
      log.error(s"unknown remote api command '$message'")
      sender ! Failed(s"unknown remote api command '$message'")
  }

  /////////////////////////////////////////////////////////////////////
  /// Messages that respond in the future
  /////////////////////////////////////////////////////////////////////
  def activeFuture = FutureReceive {

    case GetUser =>
      waitUntil(api.getSelf)(_.getUser != null) map { self => Successful(self.getUser.getId) }

    case GetUserName =>
      waitUntil(api.getSelf)(_.getUser != null) map { self => Successful(self.getUser.getUsername)}

    case GetConv(name) =>
      waitUntil(convs)(_ => convExistsByName(name)) map { _ =>
        Successful(findConvByName(name).data.remoteId.str)
      }

    case GetMessages(rConvId) =>
      whenConversationExistsFuture(rConvId) { conv =>
        for {
          idx <- zmessaging.messagesStorage.msgsIndex(conv.id)
          cursor <- idx.loadCursor
        } yield {

          ConvMessages(Array.tabulate(cursor.size) { i =>
            val m = cursor(i)
            MessageInfo(m.message.id, m.message.msgType, m.message.time)
          })
        }
      }

    case CreateGroupConversation(users@_*) =>
      zmessaging.convsUi.createGroupConversation(ConvId(), users) map { _ => Successful }

    case ClearConversation(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.clear()
        Successful
      }

    case SendText(remoteId, msg) =>
      whenConversationExists(remoteId) { conv =>
        zmessaging.convsUi.sendMessage(conv.id, msg)
        Successful
      }

    case UpdateText(msgId, text) =>
      zmessaging.messagesStorage.getMessage(msgId) flatMap {
        case Some(msg) if msg.userId == zmessaging.selfUserId =>
          zmessaging.convsUi.updateMessage(msg.convId, msgId, text) map { _ => Successful }
        case Some(_) =>
          Future successful Failed("Can not update messages from other user")
        case None =>
          Future successful Failed("No message found with given id")
      }

    case DeleteMessage(convId, msgId) =>
      whenConversationExistsFuture(convId) { conv =>
       zmessaging.messagesStorage.getMessage(msgId) flatMap {
          case Some(msg) =>
            zmessaging.convsUi.deleteMessage(conv.id, msgId) map { _ => Successful }
          case None =>
            Future successful Failed("No message found with given id")
        }
      }

    case SendGiphy(convId, searchQuery) =>
      whenConversationExistsFuture(convId) { conv =>
        searchQuery match {
          case "" =>
            waitUntil(api.getGiphy.random())(_.isReady == true) map { results =>
              zmessaging.convsUi.sendMessage(conv.id, "Via giphy.com")
              zmessaging.convsUi.sendMessage(conv.id, results.head)
              Successful
            }

          case _ =>
            waitUntil(api.getGiphy.search(searchQuery))(_.isReady == true) map { results =>
              zmessaging.convsUi.sendMessage(conv.id, "%s · via giphy.com".format(searchQuery))
              zmessaging.convsUi.sendMessage(conv.id, results.head)
              Successful
            }
        }
      }

    case RecallMessage(convId, msgId) =>
      whenConversationExistsFuture(convId) { conv =>
        zmessaging.convsUi.recallMessage(conv.id, msgId).map { _ =>
          Successful
        }
      }

    case AcceptConnection(userId) =>
      Option(userId) match {
        case Some(_) =>
          waitUntil(api.getUser(userId.str))(_.getConnectionStatus == ConnectionStatus.PendingFromOther) map { user =>
            user.acceptConnection()
            Successful
          }
        case None =>
          Future(Failed("UserId cannot be null"))
      }

    case CancelConnection(userId) =>
      Option(userId) match {
        case Some(_) =>
          waitUntil(api.getUser(userId.str))(_.getConnectionStatus == ConnectionStatus.PendingFromUser) map { user =>
            user.cancelConnection()
            Successful
          }
        case None =>
          Future(Failed("UserId cannot be null"))
      }

    case SendImage(remoteId, path) =>
      whenConversationExists(remoteId) { conv =>
        zmessaging.convsUi.sendMessage(conv.id, ui.images.createImageAssetFrom(IoUtils.toByteArray(new FileInputStream(path))))
        Successful
      }

    case SendImageData(remoteId, bytes) =>
      whenConversationExistsFuture(remoteId) { conv =>
        zmessaging.convsUi.sendMessage(conv.id, ui.images.createImageAssetFrom(bytes)).map { _ =>
          Successful
        }
      }

    case SendAsset(remoteId, bytes, mime, name, delay) =>
      whenConversationExistsFuture(remoteId) { conv =>
        delayNextAssetPosting.set(delay)
        val asset = impl.AssetForUpload(AssetId(), Some(name), Mime(mime), Some(bytes.length.toLong)){
          _ => new ByteArrayInputStream(bytes)
        }

        zmessaging.convsUi.sendMessage(conv.id, asset, DoNothingAndProceed).map(_.fold2(Failed("no message sent"), m => Successful(m.id.str)))
      }

    case SendLocation(remoteId, lon, lat, name, zoom) =>
      whenConversationExistsFuture(remoteId) { conv =>
        zmessaging.convsUi.sendMessage(conv.id, new MessageContent.Location(lon, lat, name, zoom)).map(_.fold2(Failed("no message sent"), m => Successful(m.id.str)))
      }

    case CancelAssetUpload(messageId) =>
      zmessaging.messagesStorage.getMessage(messageId).mapSome(_.assetId).flatMapSome { assetId =>
        zmessaging.assets.cancelUpload(assetId, messageId)
      }.map {
        case Some(()) => Successful
        case None     => Failed("upload not canceled: message not found or no asset ID present")
      }

    case SendFile(remoteId, path, mime) =>
      whenConversationExistsFuture(remoteId) { conv =>
        val file = new File(path)
        val assetId = AssetId()
        zmessaging.cache.addStream(CacheKey(assetId.str), new FileInputStream(file), Mime(mime)).map { cacheEntry =>
          Mime(mime) match {
            case Mime.Image() =>
              zmessaging.convsUi.sendMessage(conv.id, api.ui.images.createImageAssetFrom(IoUtils.toByteArray(cacheEntry.inputStream)))
              Successful
            case _ =>
              val asset = impl.AssetForUpload(assetId, Some(file.getName), Mime(mime), Some(file.length())) {
                _ => new FileInputStream(file)
              }
              zmessaging.convsUi.sendMessage(conv.id, asset, DoNothingAndProceed)
              Successful
          }
        }
      }

    case AddMembers(remoteId, users@_*) =>
      whenConversationExistsFuture(remoteId) { conv =>
        zmessaging.convsUi.addConversationMembers(conv.id, users).map { _ =>
          Successful
        }
      }

    case Knock(remoteId) =>
      whenConversationExistsFuture(remoteId) { conv =>
        zmessaging.convsUi.knock(conv.id).map { _ =>
          Successful
        }
      }

    case SetEphemeral(remoteId, expiration) =>
      whenConversationExistsFuture(remoteId) { conv =>
        zmessaging.convsUi.setEphemeral(conv.id, expiration).map { _ =>
          Successful
        }
      }

    case MarkEphemeralRead(convId, messageId) =>
      zmessaging.ephemeral.onMessageRead(messageId) map {
        case Some(_) => Successful
        case None    => Failed(s"message not found with id: $messageId")
      }

    case Typing(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.getInputStateIndicator.textChanged()
        Successful
      }

    case ClearTyping(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.getInputStateIndicator.textCleared()
        Successful
      }

    case ArchiveConv(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.setArchived(true)
        Successful
      }

    case UnarchiveConv(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.setArchived(false)
        Successful
      }

    case MuteConv(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.setMuted(true)
        Successful
      }

    case UnmuteConv(remoteId) =>
      whenConversationExists(remoteId) { conv =>
        conv.setMuted(false)
        Successful
      }

    case UpdateProfileImage(path) =>
      whenSelfLoaded { self =>
        self.setPicture(api.ui.images.createImageAssetFrom(IoUtils.toByteArray(getClass.getResourceAsStream(path))))
        Successful
      }

    case ClearProfileImage =>
      whenSelfLoaded { self =>
        self.clearPicture()
        Successful
      }

    case UpdateProfileName(name) =>
      whenSelfLoaded { self =>
        self.setName(name)
        Successful
      }

    case UpdateProfileUserName(userName) =>
      val p = Promise[ActorMessage]()
      waitUntil(api.getSelf)(_.getUser != null).map { self =>
        self.setUsername(userName, new CredentialsUpdateListener {
          override def onUpdateFailed(code: Int, message: String, label: String): Unit = p.success(Failed(s"unable to update user name: $code, $message, $label"))

          override def onUpdated(): Unit = p.success(Successful)
        })
      }
      p.future

    case UpdateProfileColor(color) =>
      whenSelfLoaded { self =>
        self.setAccent(color)
        Successful
      }

    case UpdateProfileEmail(email) =>
      whenSelfLoaded { self =>
        self.setEmail(email, new CredentialsUpdateListener {
          override def onUpdateFailed(code: Int, message: String, label: String): Unit = ()

          override def onUpdated(): Unit = ()
        })
        Successful
      }

    case SetMessageReaction(remoteId, messageId, action) =>
      zmessaging.messagesStorage.getMessage(messageId) flatMap {
        case Some(msg) if action == Liking.Action.Like =>
          zmessaging.reactions.like(msg.convId, messageId) map { _ => Successful }
        case Some(msg) =>
          zmessaging.reactions.unlike(msg.convId, messageId) map { _ => Successful }
        case None =>
          Future successful Failed("No message found with given id")
      }

    case SetDeviceLabel(label) =>
      waitUntil(api.getSelf.getOtrClient)(!_.isEmpty) map { client =>
        client.get.setLabel(label)
        Successful
      }

    case DeleteDevice(clientId, password) =>
      def find(cs: CoreList[OtrClient]) = cs.find(_.asInstanceOf[com.waz.api.impl.otr.OtrClient].clientId.str == clientId)
      waitUntil(api.getSelf.getOtherOtrClients)(find(_).isDefined) map find flatMap {
        case None => successful(Failed(s"Client not found: $clientId"))
        case Some(client) =>
          val p = Promise[ActorMessage]()
          client.delete(password, new DeleteCallback {
            override def onClientDeleted(client: OtrClient): Unit = p trySuccess Successful
            override def onDeleteFailed(error: String): Unit = p trySuccess Failed(s"DeleteDevice failed with error: $error")
          })
          p.future
      }

    case DeleteAllOtherDevices(password) =>
      optZms.fold[Future[ActorMessage]](successful(Failed("no zmessaging"))) { zms =>
        for {
          clients       <- zms.otrClientsStorage.getClients(zms.selfUserId).map(_.map(_.id))
          others         = clients.filter(_ != zms.clientId)
          responses     <- traverseSequential(others)(zms.otrClientsService.deleteClient(_, password))
          failures       = responses.collect { case Left(err) => s"[unable to delete client: ${err.message}, ${err.code}, ${err.label}]" }
        } yield if (failures.isEmpty) Successful else Failed(failures mkString ", ")
      }

    case GetDeviceId() =>
      waitUntil(api.getSelf.getOtrClient)(!_.isEmpty) map { client =>
        Successful(client.get.getId);
      }

    case GetDeviceFingerPrint() =>
      waitUntil(api.getSelf.getOtrClient)(!_.isEmpty) flatMap { client =>
        waitUntil(client.get.getFingerprint)(!_.isEmpty) map { fingerPrint =>
          Successful(new String(fingerPrint.get.getRawBytes))
        }
      }

    case AwaitSyncCompleted =>
      api.zmessaging flatMap {
        case None =>  successful(Failed("no zmessaging"))
        case Some(zms) => zms.syncContent.syncJobs.filter(_.isEmpty).head map { _ => Successful }
      }

    case ResetQueueStats =>
      successful({
              com.waz.threading.DispatchQueueStats.reset()
              Successful
            })

    case GetQueueStats =>
      println(s"dispatch queue stats")
      DispatchQueueStats.printStats(10)
      successful({
              QueueStats(DispatchQueueStats.report(10).toArray)
            })

    case ForceAddressBookUpload =>
      for {
        _ <- zmessaging.contacts.lastUploadTime := Some(Instant.EPOCH)
        _ <- zmessaging.contacts.requestUploadIfNeeded()
      } yield Successful
  }

  def whenConversationsLoaded(task: ConversationsList => ActorMessage): Unit = {
    waitUntil(convs)(_.size > 0) map task
  }

  def whenSelfLoaded(task: Self => ActorMessage): Future[Any] = {
    waitUntil(api.getSelf)(_.getUser != null) map task
  }

  def whenConversationExists(remoteId: RConvId)(task: IConversation => ActorMessage): Future[Any] = {
    Option(remoteId) match {
      case Some(_) =>
        waitUntil(convs)(_ => convExistsById(remoteId)) map { _ =>
          task(findConvById(remoteId))
        }
      case None =>
        Future(Failed("Conversation remoteId cannot be null"))
    }
  }

  def whenConversationExistsFuture(remoteId: RConvId)(task: IConversation => Future[ActorMessage]): Future[Any] = {
    Option(remoteId) match {
      case Some(_) =>
        waitUntil(convs)(_ => convExistsById(remoteId)) flatMap { _ =>
          task(findConvById(remoteId))
        }
      case None =>
        Future(Failed("Conversation remoteId cannot be null"))
    }
  }

  // Should not use withConv directly in Endpoint, because which will not wait until convId exist
  def withConv[A](id: RConvId)(f: ConversationData => Future[A]) =
    getConv(id) flatMap f map { _ => Successful }

  // Should not use withConv directly in Endpoint, because which will not wait until convId exist
  def getConv(id: RConvId) = zmessaging.convsContent.convByRemoteId(id) flatMap {
    case None =>
      log.warning(s"rconv id not found: $id")
      zmessaging.convsContent.convById(ConvId(id.str)) collect { case Some(conv) => conv }
    case Some(conv) =>
      successful(conv)
  }

  def FutureReceive(receive: PartialFunction[Any, Future[Any]]): Receive = {
    case message if receive.isDefinedAt(message) => respondInFuture(receive(message))
  }

  def respondInFuture[S](f: Future[S]) = {
    val senderRef = sender()
    f.onComplete {
      case util.Success(message) =>
        senderRef ! message
      case util.Failure(cause) =>
        log.error(cause, "future receive failed")
        senderRef ! Failed(s"future receive failed: ${cause.getMessage}")
    }
  }

  private var listeners = Set.empty[UpdateListener] // required to keep references to the listeners as waitUntil manages to get them garbage-collected
  private var delay = CancellableFuture.cancelled[Unit]()

  def waitUntil[S <: UiObservable](observable: S, timeout: FiniteDuration = 120.seconds)(check: S => Boolean): Future[S] = {
    Threading.assertUiThread()
    val promise = Promise[S]()

    def onUpdated: Unit = {
      Threading.assertUiThread()
      if (check(observable)) {
        promise.trySuccess(observable)
      }
    }

    val listener = new UpdateListener {
      override def updated(): Unit = onUpdated
    }
    listeners += listener
    observable.addUpdateListener(listener)

    onUpdated

    delay = CancellableFuture.delay(timeout)
    delay.onSuccess { case _ =>
      promise.tryFailure(new Exception(s"Waituntil did not complete before timeout of : ${timeout.toSeconds} seconds"))
    }

    promise.future.andThen {
      case _ =>
        observable.removeUpdateListener(listener)
        listeners -= listener
        delay.cancel()("wait_until")
    }
  }
}
