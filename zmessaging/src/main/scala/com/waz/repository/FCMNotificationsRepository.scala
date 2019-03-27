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
package com.waz.repository

import com.waz.content.Database
import com.waz.db.Col.{id, text, timestamp}
import com.waz.db.Dao2
import com.waz.model.{FCMNotification, Uid}
import com.waz.threading.Threading
import com.waz.utils.wrappers.DBCursor
import org.threeten.bp.Instant

import scala.concurrent.{ExecutionContext, Future}

trait FCMNotificationsRepository {
  def storeNotificationState(id: Uid, stage: String, timestamp: Instant): Future[Unit]
  def getPreviousStageTime(id: Uid, stage: String): Future[Option[Instant]]
  def deleteAllWithId(id: Uid): Future[Unit]
  def exists(ids: Set[Uid]): Future[Set[Uid]]
  def trimExcessRows(stage: String): Future[Unit]
}

class FCMNotificationsRepositoryImpl(implicit db: Database) extends FCMNotificationsRepository {

  import FCMNotificationsRepository._
  import FCMNotificationsDao._
  import com.waz.model.FCMNotification._

  private implicit val ec: ExecutionContext = Threading.Background

  override def getPreviousStageTime(id: Uid, stage: String): Future[Option[Instant]] =
    db.read { implicit db =>
      prevStage(stage).flatMap { s => getById(id, s).map(_.stageStartTime) }
    }

  override def storeNotificationState(id: Uid, stage: String, timestamp: Instant): Future[Unit] =
    db.apply(insertOrIgnore(FCMNotification(id, stage, timestamp))(_)).map(_ => ())

  override def deleteAllWithId(id: Uid): Future[Unit] = db.apply(deleteEvery(everyStage.map((id, _)))(_))

  def exists(ids: Set[Uid]): Future[Set[Uid]] = db.read { implicit db =>
    iterating(FCMNotificationsDao.findInSet(Id, ids)).acquire(_.map(_.id).toSet)
  }

  override def trimExcessRows(stage: String): Future[Unit] =
    if(stage == FCMNotification.FinishedPipeline) {
      db.read(FCMNotificationsDao.list(_)).flatMap { case rows if rows.size > maxRows =>
        val rowsToRemove = getOldestExcessRows(rows).map(p => (p.id, p.stage))
        db.apply(FCMNotificationsDao.deleteEvery(rowsToRemove)(_))
      }
    } else Future.successful(())

}

object FCMNotificationsRepository {

  val maxRows = 1000

  implicit object FCMNotificationsDao extends Dao2[FCMNotification, Uid, String] {
    val Id = id[Uid]('_id).apply(_.id)
    val Stage = text('stage)(_.stage)
    val StageStartTime = timestamp('stage_start_time)(_.stageStartTime)

    override val idCol = (Id, Stage)
    override val table = Table("FCMNotifications", Id, Stage, StageStartTime)

    override def apply(implicit cursor: DBCursor): FCMNotification =
      FCMNotification(Id, Stage, StageStartTime)
  }

  /** This method is here, rather than in the class, to facilitate unit testing **/
  def getOldestExcessRows(rows: Vector[FCMNotification], max: Int = maxRows): Seq[FCMNotification] = {
    rows
      .sortWith { case (t1, t2) => t1.stageStartTime.isBefore(t2.stageStartTime)}
      .take(rows.size - max)
  }
}

