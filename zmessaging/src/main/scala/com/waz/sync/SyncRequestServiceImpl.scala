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

import android.util.Log
import com.waz.ZLog._
import com.waz.api
import com.waz.api.ZmsVersion
import com.waz.api.SyncState
import com.waz.api.impl.SyncIndicator
import com.waz.model.sync._
import com.waz.model.{AccountId, ConvId, SyncId}
import com.waz.service.{NetworkModeService, ReportingService, ZmsLifeCycle}
import com.waz.sync.SyncRequestServiceImpl.SyncMatcher
import com.waz.sync.queue.{SyncContentUpdater, SyncScheduler, SyncSchedulerImpl}
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.Context

import scala.concurrent.Future

trait SyncRequestService {
  def scheduler: SyncScheduler
  def addRequest(job: SyncJob, forceRetry: Boolean = false): Future[SyncId]
}


class SyncRequestServiceImpl(context:   Context,
                             accountId: AccountId,
                             content:   SyncContentUpdater,
                             network:   NetworkModeService,
                             sync: =>   SyncHandler,
                             reporting: ReportingService,
                             lifecycle: ZmsLifeCycle) extends SyncRequestService {

  private implicit val tag = logTagFor[SyncRequestServiceImpl]
  private implicit val dispatcher = new SerialDispatchQueue(name = "SyncDispatcher")

  override val scheduler: SyncScheduler = new SyncSchedulerImpl(context, accountId, content, network, this, sync, lifecycle)

  reporting.addStateReporter { pw =>
    content.listSyncJobs flatMap { jobs =>
      pw.println(s"SyncJobs for account $accountId:")
      jobs.toSeq.sortBy(_.timestamp) foreach { job =>
        pw.println(job.toString)
      }

      pw.println("---")
      scheduler.report(pw)
    }
  }

  override def addRequest(job: SyncJob, forceRetry: Boolean = false): Future[SyncId] = content.addSyncJob(job, forceRetry).map(_.id)

  def listJobs = content.syncJobs.map(_.values.toSeq.sortBy(j => (j.timestamp, j.priority)))

  //only print to AndroidLog directly - don't want to flood our internal log
  def logJobs() = if (ZmsVersion.DEBUG) {
    for {
      rep  <- scheduler.reportString
      jobs <- listJobs.head
    } yield {
      Log.d("SyncJobs", rep)
      jobs.foreach { j =>
        Log.d("SyncJobs", j.toString)
      }
    }
  }

  def syncState(matchers: Seq[SyncMatcher]): Signal[SyncIndicator.Data] =
    content.syncJobs map { _.values.filter(job => matchers.exists(_.apply(job))) } map { jobs =>
      val state = if (jobs.isEmpty) SyncState.COMPLETED else jobs.minBy(_.state.ordinal()).state
      SyncIndicator.Data(state, api.SyncIndicator.PROGRESS_UNKNOWN, jobs.flatMap(_.error).toSeq)
    }
}

object SyncRequestServiceImpl {
  val MaxSyncAttempts = 20

  case class SyncMatcher(cmd: SyncCommand, convId: Option[ConvId]) {

    private def convMatches(job: SyncJob) = job.request match {
      case req: ConversationReference => convId forall (_ == req.convId)
      case _ => true
    }

    def apply(job: SyncJob) = job.request.cmd == cmd && convMatches(job)
  }
}
