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
package com.waz.service.push

import com.waz.content.Database
import com.waz.db.Col.{id, text, timestamp}
import com.waz.db.Dao2
import com.waz.model.Uid
import com.waz.threading.Threading
import com.waz.utils.wrappers.DBCursor
import com.waz.utils.Identifiable
import org.threeten.bp.Instant

import scala.concurrent.{ExecutionContext, Future}


/**
  * @param stageStartTime instant the push notification was received at stage
  * @param stage the stage the notification was in at time `receivedAt`
  */
case class FCMNotification(override val id: Uid,
                           stage:           String,
                           stageStartTime:  Instant) extends Identifiable[Uid]

trait FCMNotificationsRepository {
  def storeNotificationState(id: Uid, stage: String, timestamp: Instant): Future[Unit]
  def getPreviousStageTime(id: Uid, stage: String): Future[Option[Instant]]
  def deleteAllWithId(id: Uid): Future[Unit]
}

class FCMNotificationsRepositoryImpl(implicit db: Database) extends FCMNotificationsRepository {

  import FCMNotificationsRepository._
  import FCMNotificationsDao._

  private implicit val ec: ExecutionContext = Threading.Background

  override def getPreviousStageTime(id: Uid, stage: String): Future[Option[Instant]] =
    db.read { implicit db =>
      prevStage(stage).flatMap { s => getById(id, s).map(_.stageStartTime) }
    }

  override def storeNotificationState(id: Uid, stage: String, timestamp: Instant): Future[Unit] =
    db.apply { implicit db =>
      insertOrIgnore(FCMNotification(id, stage, timestamp))
    }.map(_ => ())

  override def deleteAllWithId(id: Uid): Future[Unit] = db.apply { implicit db =>
    deleteEvery(everyStage.map((id, _)))
  }
}

object FCMNotificationsRepository {

  val Pushed = "pushed"
  val Fetched = "fetched"
  val StartedPipeline = "startedPipeline"
  val FinishedPipeline = "finishedPipeline"
  val everyStage: Seq[String] = Seq(Pushed, Fetched, StartedPipeline, FinishedPipeline)

  def prevStage(stage: String): Option[String] = stage match {
    case StartedPipeline => Some(Fetched)
    case Fetched => Some(Pushed)
    case _ => None
  }

  implicit object FCMNotificationsDao extends Dao2[FCMNotification, Uid, String] {
    val Id = id[Uid]('_id).apply(_.id)
    val Stage = text('stage)(_.stage)
    val StageStartTime = timestamp('stage_start_time)(_.stageStartTime)

    override val idCol = (Id, Stage)
    override val table = Table("FCMNotifications", Id, Stage, StageStartTime)

    override def apply(implicit cursor: DBCursor): FCMNotification =
      FCMNotification(Id, Stage, StageStartTime)
  }

}

