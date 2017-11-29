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
package com.waz.api

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.serialization.Serialization
import akka.util.Timeout
import android.database.Cursor
import com.typesafe.config.ConfigFactory
import com.waz.ZLog.ImplicitTag._
import com.waz.content.Likes
import com.waz.model.AccountData.AccountDataDao
import com.waz.model.MessageData.MessageDataDao
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.provision.ActorMessage.{ReleaseRemotes, SpawnRemoteDevice, WaitUntilRegistered}
import com.waz.provision._
import com.waz.service._
import com.waz.service.otr.CryptoBoxService
import com.waz.testutils.Implicits._
import com.waz.testutils.RoboPermissionProvider
import com.waz.testutils.TestApplication.notificationsSpy
import com.waz.threading.Threading
import com.waz.ui.UiModule
import com.waz.utils._
import com.waz.utils.events.EventContext
import com.waz.znet._
import com.waz.{RoboProcess, RobolectricUtils, ShadowLogging}
import net.hockeyapp.android.Constants
import org.scalatest._
import org.scalatest.enablers.{Containing, Emptiness, Length}
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.{PartialFunction => =/>}

trait ApiSpec extends BeforeAndAfterEach with BeforeAndAfterAll with Matchers with RobolectricTests with ShadowLogging with RobolectricUtils { suite: Suite with Alerting with Informing =>
  import Threading.Implicits.Background
  override protected lazy val logfileBaseDir: File = new File("target/logcat/integration")

  val otrTempClient = false
  val autoLogin = true
  val initBehaviour: InitBehaviour = InitOnceBeforeAll

  lazy val timeouts = new Timeouts

  sealed protected trait InitBehaviour
  protected case object InitOnceBeforeAll extends InitBehaviour
  protected case object InitEveryTime extends InitBehaviour
  protected case object InitManually extends InitBehaviour

  lazy val zmessagingFactory: ZMessagingFactory = new ZMessagingFactory(globalModule) {
    override def zmessaging(teamId: Option[TeamId], clientId: ClientId, user: UserModule, st: StorageModule, cb: CryptoBoxService): ZMessaging = new ApiZMessaging(teamId, clientId, user, st, cb)
  }

  class ApiZMessaging(teamId: Option[TeamId], clientId: ClientId, user: UserModule, st: StorageModule, cb: CryptoBoxService) extends ZMessaging(teamId, clientId, user, st, cb) {
    override lazy val eventPipeline = new EventPipelineImpl(Vector(otrService.eventTransformer), events =>
      returning(eventScheduler.enqueue(events))(_ => eventSpies.get.foreach(pf => events.foreach(e => pf.applyOrElse(e, (_: Event) => ())))))

    override lazy val otrClient = new com.waz.sync.client.OtrClient(zNetClient) {
      override private[waz] val PermanentClient = !otrTempClient
    }
  }

  private lazy val eventSpies = new AtomicReference(Vector.empty[Event =/> Unit])

  def testBackend: BackendConfig = BackendConfig.StagingBackend
  lazy val testClient = new AsyncClientImpl(wrapper = TestClientWrapper())

  lazy val globalModule: GlobalModuleImpl = new ApiSpecGlobal

  class ApiSpecGlobal extends GlobalModuleImpl(context, testBackend) {
    override lazy val clientWrapper: Future[ClientWrapper] = TestClientWrapper()
    override lazy val client: AsyncClientImpl = testClient
    override lazy val timeouts: Timeouts = suite.timeouts

    ZMessaging.currentGlobal = this

    override lazy val factory: ZMessagingFactory = zmessagingFactory
  }

  lazy val accounts = new AccountsServiceImpl(globalModule)

  implicit lazy val ui = returning(new UiModule(accounts)) { ZMessaging.currentUi = _ }

  var api: impl.ZMessagingApi = _
  private var self = None: Option[Self]
  def email = s"email@test.com"
  def password = "test_pass"

  def zmessaging = Await.result(api.zmessaging, 5.seconds).get

  def lastMessage(conv: ConvId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.messagesStorage.getLastMessage(conv)
    case None => Future.successful(Option.empty[MessageData])
  }, 5.seconds)

  def getUnreadCount(conv: ConvId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.convsStorage.get(conv).map(_.fold(0)(_.unreadCount.messages))
    case None => Future.successful(0)
  }, 5.seconds)

  def listMessages(conv: ConvId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.storage.db { db => MessageDataDao.list(MessageDataDao.findMessages(conv)(db)).sortBy(_.time) }
    case None => Future.successful(Vector.empty[MessageData])
  }, 5.seconds)

  def lastRead(conv: ConvId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.convsStorage.get(conv).map(_.fold(Instant.EPOCH)(_.lastRead))
    case None => Future.successful(Instant.EPOCH)
  }, 5.seconds)

  def getMessage(id: MessageId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.messagesStorage.get(id)
    case None => Future.successful(Option.empty[MessageData])
  }, 5.seconds)

  def getLikes(id: MessageId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.reactionsStorage.getLikes(id)
    case None => Future.successful(Likes(id, Map.empty))
  }, 5.seconds)

  def getAsset(id: AssetId) = Await.result(api.zmessaging.flatMap {
    case Some(zms) => zms.assetsStorage.get(id)
    case None => Future.successful(Option.empty[AssetData])
  }, 5.seconds)

  implicit class RichMessage(msg: MessageData) {
    def like() = zmessaging.reactions.like(msg.convId, msg.id)
    def unlike() = zmessaging.reactions.unlike(msg.convId, msg.id)
    def update(text: String) = zmessaging.convsUi.updateMessage(msg.convId, msg.id, text)
  }

  def netClient = zmessaging.zNetClient

  def znetClientFor(email: String, password: String): ZNetClient = new ZNetClientImpl(None, new AsyncClientImpl(wrapper = TestClientWrapper()), BackendConfig.StagingBackend.baseUrl)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Threading.AssertsEnabled = false

    ZMessaging.context = context
    Constants.loadFromContext(context)
    ZMessaging.currentUi = ui
    ZMessaging.currentAccounts = accounts
    ui.global.permissions.setProvider(new RoboPermissionProvider)

    if (initBehaviour == InitOnceBeforeAll) createZMessagingAndLogin()

    implicit val eventContext = EventContext.Global

    val zms = ZMessaging.currentUi.currentZms.collect { case Some(z) => z }
    zms.map(_.notifications).flatMap(_.notifications)(notificationsSpy.gcms :+= _)
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    if (initBehaviour == InitEveryTime) createZMessagingAndLogin()
  }

  protected def createZMessagingAndLogin(): Unit = {
    ZMessaging.context = context
    ZMessaging.currentGlobal = globalModule
    ZMessaging.currentUi = ui
    ZMessaging.currentAccounts = accounts

    api = new impl.ZMessagingApi()
    api.onCreate(context)
    initApi()

    withClue("auto login failed") {
      if (autoLogin && !self.exists(_.isLoggedIn)) {
        login() shouldEqual true
      }
    }
  }

  override protected def afterAll(): Unit = {
    if (initBehaviour == InitOnceBeforeAll) logoutAndDestroy()
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    if (initBehaviour == InitEveryTime) logoutAndDestroy()
    super.afterEach()
  }

  protected def logoutAndDestroy(): Unit = {
//    var maybeZms = Await.result(api.ui.getCurrent, 5.seconds)
//    api.logout()
//    pauseApi()
//    api.onDestroy()
//    deleteUserAfterLogout()
//    awaitUi(500.millis)
//    maybeZms.foreach { zms =>
//      awaitUi(zms.websocket.connected.currentValue.forall(_ == false), "websocket connection should be disconnected")
//      zms.storage.db.close()
//      zms.global.storage.close()
//      api.ui.uiCache.clear()
//    }
//    accounts.activeAccountPref := None
//    accounts.accountMap.clear()
//    api = null
//    ZMessaging.context = null
//    maybeZms = null
//    System.gc()
  }

  def deleteUserAfterLogout(): Unit = Await.result(globalModule.storage(AccountDataDao.deleteForEmail(EmailAddress(email))(_)), 10.seconds)

  def withInitializedApi[A](f: => A): A = {
    createZMessagingAndLogin()
    try f finally logoutAndDestroy()
  }

  def initApi() = {
    self = None
    api.onResume()

    api.onInit(new InitListener {
      override def onInitialized(user: Self): Unit = { self = Some(user) }
    })

    withDelay(self should be('defined))(30.seconds)

    self.exists(_.isLoggedIn)
  }

  def pauseApi() = {
    try {
      api.onPause()
    } catch {
      case er: AssertionError => er.printStackTrace(System.err)
    }
  }

  def login(email: String = this.email, password: String = this.password)(implicit timeout: Timeout = 10.seconds): Boolean =
    login(CredentialsFactory.emailCredentials(email, password))

  def login(credentials: Credentials)(implicit timeout: Timeout): Boolean = {
    @volatile var selfUser: Option[Self] = None
    @volatile var error: Option[ErrorResponse] = None

//    api.login(credentials, new LoginListener {
//      override def onFailed(code: Int, message: String, label: String): Unit = {
//        error = Some(impl.ErrorResponse(code, message, label))
//      }
//
//      override def onSuccess(user: Self): Unit = {
//        selfUser = Some(user)
//      }
//    })

    try {
      awaitUi { error.isDefined || selfUser.isDefined }(15.seconds)
      if (error.isDefined) alert(s"Login failed: $error")
      else {
        awaitUi { selfUser.get.accountActivated } (10.seconds)
        awaitUi { api.account.isDefined }
        awaitUiFuture { api.account.get.zmessaging.filter(_.isDefined).head }
        awaitUi { Await.result(api.zmessaging, 5.seconds).isDefined }
        awaitUiFuture { zmessaging.websocket.connected.filter(_ == true).head }
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        alert(s"WARNING: login failed or websocket not connected, error: $error, self: $selfUser, websocket connected: ${zmessaging.websocket.connected.currentValue}")
    }

    error.foreach { error => alert(s"WARNING: login failed with error: $error") }

    returning(selfUser.isDefined) { _ => awaitUi(1.second) }
  }

  implicit object CoreListEmptiness extends Emptiness[CoreList[_]] {
    override def isEmpty(thing: CoreList[_]): Boolean = thing.size == 0
  }
  implicit object ImageAssetEmptiness extends Emptiness[ImageAsset] {
    override def isEmpty(thing: ImageAsset): Boolean = thing.isEmpty
  }

  implicit object CoreListContaining extends Containing[CoreList[_]] {
    override def contains(container: CoreList[_], element: Any): Boolean = container.contains(element)
    override def containsOneOf(container: CoreList[_], elements: Seq[Any]): Boolean = elements.count(container.contains) == 1
    override def containsNoneOf(container: CoreList[_], elements: Seq[Any]): Boolean = elements.forall(!container.contains(_))
  }

  implicit object CursorEmptiness extends Emptiness[Cursor] {
    override def isEmpty(thing: Cursor): Boolean = thing.getCount == 0
  }

  implicit val coreListLen = new Length[CoreList[_]] {
    override def lengthOf(obj: CoreList[_]): Long = obj.size()
  }
  implicit val coreListEmpty = new Emptiness[CoreList[_]] {
    override def isEmpty(thing: CoreList[_]): Boolean = thing.size() == 0
  }

  def whilePaused(f: => Unit): Unit = {
    api.onPause()
    awaitUiFuture(zmessaging.websocket.connected.filter(c => !c).head)
    try f finally api.onResume()
  }

  def withPush(pf: PartialFunction[Event, Boolean], zms: ZMessaging = zmessaging)(body: => Unit)(implicit timeout: Timeout = 15.seconds) = {
    implicit val ev = EventContext.Global
    @volatile var pushReceived = false

    compareAndSet(eventSpies)(_ :+ pf.andThen(pushReceived = _))

    body

    withClue("Push notification was not received") {
      withDelay(pushReceived shouldEqual true)(timeout)
    }
  }
}


trait ActorSystemSpec extends BeforeAndAfterAll { suite: Suite with Alerting with Informing =>

  implicit val timeout: com.waz.RobolectricUtils.Timeout = 15.seconds
  implicit val akkaTimeout = Timeout(timeout)

  def testBackend: BackendConfig

  //Create a CoordinatorActor with its system
  lazy val coordinatorSystem: ActorSystem = setUpCoordinatorSystem()
  lazy val coordinatorActor: ActorRef = setUpCoordinatorActor()

  def setUpCoordinatorSystem(configFileName: String = "actor_coordinator"): ActorSystem = {
    val config = ConfigFactory.load(configFileName)
    ActorSystem.create("CoordinatorSystem", config)
  }

  def setUpCoordinatorActor(): ActorRef = {
    coordinatorSystem.actorOf(Props[CoordinatorActor], "coordinatorActor")
  }

  def registerProcess(processName: String, maxWait: FiniteDuration = 30.seconds, backend: BackendConfig = testBackend, otrOnly: Boolean = false)(implicit akkaTimeout: akka.util.Timeout = Timeout(maxWait)): ActorRef = {
    val serialized = Serialization.serializedActorPath(coordinatorActor)
    RoboProcess[RemoteProcess](processName, serialized, backend.environment, otrOnly.toString)

    Await.result(coordinatorActor.ask(WaitUntilRegistered(processName))(maxWait).mapTo[ActorRef], maxWait)
  }

  def awaitRemote(processName: String, maxWait: FiniteDuration = 30.seconds)(implicit akkaTimeout: akka.util.Timeout = Timeout(maxWait)): ActorRef =
    Await.result(coordinatorActor.ask(WaitUntilRegistered(processName))(maxWait).mapTo[ActorRef], maxWait)

  def registerDevice(deviceName: String, remoteProcess: ActorRef): ActorRef = spawnDeviceOnProcess(deviceName, remoteProcess)

  def spawnDeviceOnProcess(deviceName: String, remoteProcessActor: ActorRef, maxWait: FiniteDuration = 30.seconds)(implicit akkaTimeout: akka.util.Timeout = Timeout(maxWait)): ActorRef = {
    assert(remoteProcessActor != null, "Requires the remote process actor to be set up properly first")
    Await.result(remoteProcessActor.ask(SpawnRemoteDevice("", deviceName))(maxWait).mapTo[ActorRef], maxWait)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    shutDownAllSystems()
  }

  def shutDownAllSystems(): Unit = {
    coordinatorActor ! ReleaseRemotes
    coordinatorSystem.shutdown()
  }
}

/**
 * [[ThreadActorSpec]]s produce multiple instances of SE per remote JVM process. This makes them quicker and easier
 * on resources, but unfortunately there's no way to split up the log files of each SE instance, making debugging
 * very difficult. See the [[ProcessActorSpec]]s below to get a debug file per SE instance.
 */
trait ThreadActorSpec extends ActorSystemSpec { suite: Suite with Alerting with Informing =>
  val otrOnly: Boolean = true
  lazy val remoteProcessActor = registerProcess(this.getClass.getSimpleName, otrOnly = otrOnly)

  def registerDevice(deviceName: String): ActorRef =
    registerDevice(deviceName, remoteProcessActor)
}

/**
 * [[ProcessActorSpec]]s create a new JVM process per device instance, each with their own log file located at:
 * target/logcat/deviceName. These will be more useful for debugging what is going wrong with any potential
 * problems on the remote.
 */
trait ProcessActorSpec extends ActorSystemSpec { suite: Suite with Alerting with Informing =>
  val otrOnly: Boolean = true

  def registerDevice(deviceName: String): ActorRef =
    registerDevice(deviceName, registerProcess(s"${this.getClass.getSimpleName}_$deviceName", otrOnly = otrOnly))

  def registerDevice(deviceName: String, otrOnly: Boolean): ActorRef =
    registerDevice(deviceName, registerProcess(s"${this.getClass.getSimpleName}_$deviceName", otrOnly = otrOnly))
}
