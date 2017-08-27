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
package com.waz.sync

import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue

import com.waz.RobolectricUtils
import com.waz.api.{NetworkMode, SyncState}
import com.waz.model._
import com.waz.model.sync.SyncJob.Priority
import com.waz.model.sync.SyncRequest._
import com.waz.model.sync.{SyncCommand => Cmd, _}
import com.waz.sync.queue.ConvLock
import com.waz.testutils.Matchers._
import com.waz.testutils.{MockUserModule, MockZMessaging}
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLog
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.Matcher
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest._
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

@Ignore class SyncRequestServiceImplSpec extends FeatureSpec with Matchers with BeforeAndAfter with RobolectricTests with RobolectricUtils with GeneratorDrivenPropertyChecks with ScalaFutures { test =>
//  import com.waz.threading.Threading.Implicits.Background
//  implicit val timeout: Timeout = 5.seconds
//
//  lazy val userModule = new MockUserModule() {
//    override lazy val syncHandler: SyncHandler = new SyncHandler {
//      override def apply(req: SyncRequest): Future[SyncResult] = {
//        requested = requested :+ req
//        syncJobHandler(req)
//      }
//      override def apply(req: SerialExecutionWithinConversation, lock: ConvLock): Future[SyncResult] = apply(req)
//    }
//  }
//
//  lazy val service = new MockZMessaging(userModule)
//
//  def accountId = service.accountId
//
//  var requested = List[SyncRequest]()
//  var syncResult: SyncResult = SyncResult.Success
//  var syncJobHandler = new PushSyncHandler
//
//  def storage = service.storage
//  def scheduler = service.syncRequests.scheduler
//  def syncExecutor = scheduler.executor
//
//  before {
//    requested = Nil
//    syncResult = SyncResult.Success
//    syncJobHandler = new PushSyncHandler
//  }
//
//  after {
//    syncJobHandler.all
//    Await.result(service.syncRequests.content.listSyncJobs map { _ foreach { job => service.syncRequests.content.removeSyncJob(job.id) } }, 5.seconds)
//    ShadowLog.stream = null
//  }
//
//  def addRequest(req: SyncRequest, priority: Int = Priority.Normal, dependsOn: Seq[SyncId] = Nil, optional: Boolean = false, attempts: Int = 0, startTime: Long = 0L, forceRetry: Boolean = false): SyncId = {
//    val id = SyncId()
//    Await.result(service.syncRequests.addRequest(SyncJob(id, req, dependsOn.toSet, priority = priority, optional = optional, attempts = attempts, startTime = startTime), forceRetry), 5.seconds)
//  }
//
//  class PushSyncHandler extends (SyncRequest => Future[SyncResult]) {
//    import scala.collection.JavaConverters._
//    val requests = new ConcurrentLinkedQueue[(SyncRequest, Promise[SyncResult])]()
//
//    override def apply(req: SyncRequest): Future[SyncResult] = {
//      val promise = Promise[SyncResult]()
//      requests.add((req, promise))
//      promise.future
//    }
//
//    def next =
//      Option(requests.poll()) map { case (req, p) => p.success(syncResult); req }
//
//    def all = {
//      val ps = requests.asScala.toVector
//      requests.clear()
//      ps map { case (req, p) => p.success(syncResult); req }
//    }
//
//    def hasRequests = !requests.isEmpty || { awaitUi(100.millis); !requests.isEmpty }
//  }
//
//  /**
//   * Executes all immediately available requests.
//   */
//  def executeScheduled(implicit timeout: Timeout) = {
//    withDelay(syncJobHandler.requests should not be empty)
//    syncJobHandler.all.toSeq
//  }
//
//  def executeAll(implicit timeout: Timeout) = Iterator.continually(if (syncJobHandler.hasRequests) executeScheduled else Nil).takeWhile(_.nonEmpty).toList.flatten
//
//  scenario("enqueue initial requests") {
//
//    addRequest(SyncConversations, priority = Priority.High)
//    addRequest(SyncSelf, priority = Priority.High)
//    addRequest(SyncConnections)
//
//    withDelay {
//      requested.take(2).toSet shouldEqual Set(SyncConversations, SyncSelf)
//      requested.toSet shouldEqual Set(SyncConversations, SyncSelf, SyncConnections)
//    }
//    executeScheduled should have size 3
//  }
//
//  scenario("serialize conversation commands processing") {
//    val handler = syncJobHandler
//
//    val conv = ConvId()
//    addRequest(PostMessage(conv, MessageId(), Instant.EPOCH))
//    addRequest(PostConvState(conv, ConversationState(None, None, None, None)))
//    addRequest(PostConvName(conv, "name"))
//
//    withDelay(requested should have size 1)
//    handler.all
//
//    withDelay(requested should have size 2)
//    handler.all
//
//    withDelay {
//      requested.map(_.cmd) shouldEqual List(Cmd.PostMessage, Cmd.PostConvState, Cmd.PostConvName)
//    } (1.second)
//    handler.all
//  }
//
//  scenario("keep order of conversation commands") {
//    val conv = ConvId()
//
//    addRequest(PostMessage(conv, MessageId(), Instant.EPOCH))
//    addRequest(PostConvState(conv, ConversationState(None, None, None, None)))
//    addRequest(PostConvName(conv, "name"))
//
//    executeScheduled should haveCommands(Cmd.PostMessage)
//    executeScheduled should haveCommands(Cmd.PostConvState)
//    executeScheduled should haveCommands(Cmd.PostConvName)
//  }
//
//  scenario("execute optional priority commands one by one") {
//    addRequest(SyncSelf, priority = Priority.Optional)
//    addRequest(SyncConnections, priority = Priority.Optional)
//    addRequest(SyncSelfClients, priority = Priority.Optional)
//    awaitUi(100.millis)
//    requested shouldEqual Seq(SyncSelf)
//    executeScheduled should haveCommands(Cmd.SyncSelf)
//    executeScheduled should haveCommands(Cmd.SyncConnections)
//    executeScheduled should haveCommands(Cmd.SyncSelfClients)
//  }
//
//  scenario("execute higher priority commands concurrently") {
//    addRequest(SyncSelf, priority = Priority.Low)
//    addRequest(SyncConnections, priority = Priority.High)
//    addRequest(SyncConnectedUsers, priority = Priority.Low)
//    addRequest(SyncSelfClients, priority = Priority.Normal)
//    awaitUi(100.millis)
//
//    executeScheduled should haveCommands(Cmd.SyncSelf, Cmd.SyncConnections, Cmd.SyncSelfClients)
//    executeScheduled should haveCommands(Cmd.SyncConnectedUsers)
//  }
//
//  scenario("update priority of dependent commands") {
//    addRequest(SyncSelf, priority = Priority.Optional)
//    val syncId = addRequest(SyncConnections, priority = Priority.Optional)
//    awaitUi(100.millis)
//    requested shouldEqual Seq(SyncSelf)
//
//    addRequest(SyncSearchQuery(SearchQuery.TopPeople), priority = Priority.High, dependsOn = Seq(syncId))
//
//    awaitUi(100.millis)
//    executeScheduled should haveCommands(Cmd.SyncSelf, Cmd.SyncConnections)
//    executeScheduled should haveCommands(Cmd.SyncSearchQuery)
//  }
//
//  scenario("update priority of dependent conversation commands") {
//    val conv = ConvId()
//    addRequest(SyncSelf, priority = Priority.Normal)
//    addRequest(PostMessage(conv, MessageId(), Instant.EPOCH), priority = Priority.Optional)
//    addRequest(PostConvState(conv, ConversationState(None, None, None, None)))
//    addRequest(PostConvName(conv, "name"), priority = Priority.High)
//    awaitUi(100.millis)
//
//    executeScheduled should haveCommands(Cmd.SyncSelf, Cmd.PostMessage)
//    executeScheduled should haveCommands(Cmd.PostConvState)
//    executeScheduled should haveCommands(Cmd.PostConvName)
//  }
//
//  scenario("Scheduling pending requests doesn't include recently failed ones.") {
//    addRequest(SyncSelf, attempts = 10, startTime = new Date().getTime + 1000L)
//    addRequest(SyncSearchQuery(SearchQuery.Recommended("meep")))
//
//    executeScheduled should haveCommands(Cmd.SyncSearchQuery)
//  }
//
//  scenario("Pending requests get executed immediately on re-login.") {
//    addRequest(SyncSelf, attempts = 10, startTime = new Date().getTime + 1000L)
//    addRequest(SyncSearchQuery(SearchQuery.TopPeople))
//    service.lifecycle.lifecycleState ! LifecycleState.Stopped
//    service.lifecycle.lifecycleState ! LifecycleState.Active
//
//    awaitUi(100.millis)
//    executeScheduled should haveCommandsSet(Cmd.SyncSelf, Cmd.SyncSearchQuery)
//  }
//
//  scenario("Request failed when offline get executed immediately on network mode change.") {
//    Await.result(service.syncRequests.addRequest(SyncJob(SyncId(), SyncSelf, startTime = new Date().getTime + 1000L, state = SyncState.FAILED, offline = true)), 5.seconds)
//    addRequest(SyncSearchQuery(SearchQuery.Recommended("meep")))
//    awaitUi(100.millis)
//    service.network.networkMode ! NetworkMode._3G
//
//    executeScheduled should haveCommandsSet(Cmd.SyncSelf, Cmd.SyncSearchQuery)
//  }
//
//  scenario("Pending requests won't get executed on change to offline mode.") {
//    addRequest(SyncSelf, attempts = 10, startTime = new Date().getTime + 1000L)
//    addRequest(SyncSearchQuery(SearchQuery.TopPeople))
//    awaitUi(100.millis)
//    service.network.networkMode ! NetworkMode.OFFLINE
//
//    executeScheduled should haveCommands(Cmd.SyncSearchQuery)
//  }
//
//  scenario("Pending post message requests get executed one by one.") {
//    val conv = ConvId()
//    val msg = MessageId(s"msg-0")
//    val msgs = (1 to 10).map(idx => MessageId(s"msg-$idx"))
//    Await.result(service.syncRequests.addRequest(SyncJob(SyncId(), PostMessage(conv, msg, Instant.EPOCH), state = SyncState.FAILED, offline = true, startTime = new Date().getTime + 1000L)), 5.seconds)
//    msgs foreach { m => addRequest(PostMessage(conv, m, Instant.EPOCH)) }
//
//    service.network.networkMode ! NetworkMode._3G
//
//    (msg +: msgs) foreach { m =>
//      val rs =executeScheduled
//      rs.map(_.cmd) shouldEqual Seq(Cmd.PostMessage)
//      rs.map(_.asInstanceOf[PostMessage].messageId) shouldEqual Seq(m)
//    }
//  }
//
//  feature("Delayed sync requests") {
//
//    scenario("Execute delayed requests only once requested time elapses") {
//      addRequest(SyncSelf, startTime = new Date().getTime + 500)
//      awaitUi(100.millis)
//      requested shouldBe empty
//      awaitUi(500.millis)
//      executeScheduled should haveCommands(Cmd.SyncSelf)
//    }
//
//    scenario("Schedule alarm for delayed requests") {
//      addRequest(SyncSelf, startTime = new Date().getTime + 5000)
//      awaitUi(100.millis)
//      requested shouldBe empty
//      val alarms = Robolectric.shadowOf(scheduler.alarmManager).getScheduledAlarms
//      withDelay {
//        alarms should have size 1
//        alarms.get(0).operation shouldEqual scheduler.alarmSyncIntent
//      }
//    }
//
//    scenario("Update delay to smaller value, when requested is re-scheduled") {
//      addRequest(SyncSelf, startTime = new Date().getTime + 5000)
//      addRequest(SyncSelf, startTime = new Date().getTime + 100)
//      requested shouldBe empty
//      awaitUi(100.millis)
//      executeScheduled should haveCommands(Cmd.SyncSelf)
//    }
//  }
//
//  feature("Retry throttling") {
//
//    scenario("Use exponential backoff when retrying a failing request") {
//      syncResult = SyncResult.Failure(None, shouldRetry = true)
//      addRequest(SyncSelf)
//      executeScheduled should haveCommands(Cmd.SyncSelf)
//      requested = Nil
//      awaitUi(3.seconds)
//      requested shouldBe empty
//      withDelay {
//        executeScheduled should haveCommands(Cmd.SyncSelf)
//      } (7.seconds)
//    }
//
//    scenario("Don't reset retries when the same request is added again") {
//      syncResult = SyncResult.Failure(None, shouldRetry = true)
//      addRequest(SyncSelf)
//      executeScheduled should haveCommands(Cmd.SyncSelf)
//      addRequest(SyncSelf)
//      requested = Nil
//      awaitUi(3.seconds)
//      requested shouldBe empty
//    }
//
//    scenario("Reset retries count when user requests forced retry") {
//      syncResult = SyncResult.Failure(None, shouldRetry = true)
//      addRequest(SyncSelf)
//      executeScheduled should haveCommands(Cmd.SyncSelf)
//      requested = Nil
//      awaitUi(1.seconds)
//      requested shouldBe empty
//      addRequest(SyncSelf, forceRetry = true)
//      executeScheduled should haveCommands(Cmd.SyncSelf)
//    }
//
//    scenario("Release priority lock on sync failure - before retrying") {
//      syncResult = SyncResult.Failure(None, shouldRetry = true)
//      addRequest(SyncSelf, priority = Priority.Optional)
//      addRequest(SyncSelfClients, priority = Priority.Optional)
//      addRequest(SyncConnections, priority = Priority.Optional)
//
//      executeScheduled shouldEqual Seq(SyncSelf)
//      executeScheduled shouldEqual Seq(SyncSelfClients)
//      executeScheduled shouldEqual Seq(SyncConnections)
//
//      syncResult = SyncResult.Success
//      requested = Nil
//
//      withDelay {
//        syncJobHandler.next
//        requested should contain theSameElementsAs Seq(SyncSelf, SyncSelfClients, SyncConnections)
//      } (10.seconds)
//    }
//  }
//
//  feature("Merging") {
//
//    def assertMerged(conv: ConvId, reqs: SyncRequest*)(merged: PartialFunction[SyncRequest, Boolean]): Unit = {
//      val lock = service.syncRequests.scheduler.queue.acquire(conv).futureValue // to make sure requests are not started too early
//
//      reqs foreach { addRequest(_) }
//
//      awaitUi(100.millis)
//      lock.release()
//
//      executeScheduled should beMatching({
//        case Seq(req: SyncRequest) if merged.isDefinedAt(req) => merged.apply(req)
//      })
//      awaitUi(100.millis)
//      syncJobHandler.requests shouldBe empty
//    }
//
//    // checks that requests are scheduled one by one and not merged (no concurrent execution)
//    def assertSequential(reqs: SyncRequest*): Unit = {
//      reqs foreach { addRequest(_) }
//      reqs foreach { r =>
//        executeScheduled shouldEqual Seq(r)
//      }
//    }
//
//    // checks that requests are scheduled all at the same time and are not merged
//    def assertConcurrent(reqs: SyncRequest*): Unit = {
//      reqs foreach { addRequest(_) }
//      executeScheduled.toSet shouldEqual reqs.toSet
//    }
//
//    scenario("Merge post requests") {
//      val convId = ConvId()
//      assertMerged(convId, PostSelfPicture(Some(AssetId())), PostSelfPicture(None)) { case PostSelfPicture(None) => true }
//      assertMerged(convId, PostConv(convId, Nil, None, None), PostConv(convId, Seq(UserId()), Some("name"), None)) { case PostConv(`convId`, Seq(_), Some("name"), None) => true }
//      assertMerged(convId, PostConvState(convId, ConversationState(archived = Some(true), archiveTime = Some(Instant.ofEpochMilli(5)))), PostConvState(convId, ConversationState(archived = Some(false), archiveTime = Some(Instant.ofEpochMilli(7))))) {
//        case PostConvState(`convId`, ConversationState(Some(false), Some(time), None, None)) => time == Instant.ofEpochMilli(7)
//      }
//    }
//
//    def withOngoing(ongoing: SyncRequest, reqs: SyncRequest*) = {
//      requested = Nil
//      addRequest(ongoing)
//      withDelay {
//        requested shouldEqual Seq(ongoing)
//      }
//      reqs foreach { addRequest(_) }
//
//      awaitUi(10.millis)
//      syncJobHandler.next shouldBe 'defined
//
//      awaitUi(100.millis)
//      if (syncJobHandler.requests.isEmpty) Nil else executeScheduled
//    }
//
//    def assertMergeWithOngoing(ongoing: SyncRequest, reqs: SyncRequest*) = {
//      withOngoing(ongoing, reqs: _*) shouldBe empty
//    }
//
//    def assertDontMergeWithOngoing(ongoing: SyncRequest, req: SyncRequest) = {
//      withOngoing(ongoing, req) shouldEqual Seq(req)
//    }
//
//    scenario("Merge ongoing post when duplicate") {
//      val asset = AssetId()
//      assertMergeWithOngoing(PostSelfPicture(Some(asset)), PostSelfPicture(Some(asset)))
//      assertDontMergeWithOngoing(PostSelfPicture(Some(AssetId())), PostSelfPicture(None))
//
//      val convId = ConvId()
//      assertDontMergeWithOngoing(PostConvState(convId, ConversationState(archived = Some(true))), PostConvState(convId, ConversationState(archived = Some(false))))
//    }
//
//    scenario("Add second conv request while first is being processed, both should be processed (sequentially)") {
//      val convId = ConvId()
//      addRequest(PostMessage(convId, MessageId(), Instant.EPOCH))
//      awaitUi(100.millis)
//      addRequest(PostMessage(convId, MessageId(), Instant.EPOCH))
//
//      executeScheduled should have size 1
//      executeScheduled should have size 1
//    }
//
//    scenario("Add overriding request when first is processed and fails later") {
//      syncResult = SyncResult.failed()
//      val assetId = AssetId()
//      addRequest(PostSelfPicture(Some(assetId)))
//      awaitUi(100.millis)
//      addRequest(PostSelfPicture(None))
//      syncJobHandler.all should have size 1
//      awaitUi(100.millis)
//
//      requested shouldEqual Seq(PostSelfPicture(Some(assetId))) // second request is not scheduled yet
//      syncResult = SyncResult.Success
//
//      executeScheduled(10.seconds) shouldEqual Seq(PostSelfPicture(None)) // second request is executed
//
//      awaitUi(1.second)
//      requested should have size 2  // no other request should be sent (it should be merged)
//    }
//
//    scenario("Add second overriding request when first fails") {
//      syncResult = SyncResult.failed()
//      val assetId = AssetId()
//      val assetId2 = AssetId()
//      addRequest(PostSelfPicture(Some(assetId)))
//      awaitUi(100.millis)
//      addRequest(PostSelfPicture(None))
//      syncJobHandler.all should have size 1
//      awaitUi(100.millis)
//
//      requested shouldEqual Seq(PostSelfPicture(Some(assetId)))
//      syncResult = SyncResult.Success
//
//      addRequest(PostSelfPicture(Some(assetId2)))
//
//      executeScheduled(10.seconds) shouldEqual Seq(PostSelfPicture(Some(assetId2))) // merged request overrides failed one
//
//      awaitUi(1.second)
//      requested should have size 2  // no other request should be sent (it should be merged)
//    }
//
//    scenario("Add second overriding request while first is being processed, only one should be processed") {
//      addRequest(PostSelfPicture(Some(AssetId())))
//      awaitUi(100.millis)
//      addRequest(PostSelfPicture(None))
//
//      syncJobHandler.all should have size 1
//      requested should have size 1
//
//      executeScheduled shouldEqual Seq(PostSelfPicture(None))
//    }
//
//    scenario("Merge multiple sync user requests") {
//      val users = Seq.tabulate(1000)(i => UserId(s"u_$i"))
//
//      import com.waz.threading.Threading.Implicits.Background
//      val f = Future.traverse(0 to 10) { _ =>
//        Future.traverse(users) { u => service.syncRequests.addRequest(SyncJob(SyncId(), SyncUser(Set(u)))) }
//      }
//      Await.result(f, 15.seconds)
//      awaitUi(1.second)
//
//      withDelay {
//        while (syncJobHandler.next.isDefined) {}
//        requested.size should be < 30
//        requested.collect { case SyncUser(us) => us } .flatten.distinct.sorted should have size 1000
//        requested.collect { case SyncUser(us) => us } foreach { _.size should be <= 45}
//      } (30.seconds)
//    }
//  }
//
//  def haveCommands(commands: Cmd*): Matcher[Seq[SyncRequest]] = be(commands.toSeq) compose (_ map (_.cmd))
//  def haveCommandsSet(commands: Cmd*): Matcher[Seq[SyncRequest]] = be(commands.toSet) compose (_.map(_.cmd).toSet)
}
