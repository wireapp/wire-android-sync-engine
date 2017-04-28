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
package com.waz.content

import android.content.Context
import com.waz.ZLog._
import com.waz.model.ConversationMemberData.ConversationMemberDataDao
import com.waz.model._
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.{AggregatingSignal, Signal}
import com.waz.utils.{CachedStorageImpl, TrimmingLruCache}

import scala.collection._
import scala.concurrent.Future

trait MembersStorage {
  def getByConv(conv: ConvId): Future[IndexedSeq[ConversationMemberData]]
}

class DefaultMembersStorage(context: Context, storage: ZmsDatabase) extends CachedStorageImpl[(UserId, ConvId), ConversationMemberData](new TrimmingLruCache(context, Fixed(1024)), storage)(ConversationMemberDataDao, "MembersStorage_Cached") with MembersStorage {
  private implicit val dispatcher = new SerialDispatchQueue(name = "MembersStorage")

  def getByConv(conv: ConvId) = find(_.convId == conv, ConversationMemberDataDao.findForConv(conv)(_), identity)

  def getByUser(user: UserId) = find(_.userId == user, ConversationMemberDataDao.findForUser(user)(_), identity)

  def activeMembers(conv: ConvId): Signal[Set[UserId]] = new AggregatingSignal[Seq[(UserId, Boolean)], Set[UserId]](onConvMemberChanged(conv), getActiveUsers(conv).map(_.toSet), { (current, changes) =>
    val (active, inactive) = changes.partition(_._2)

    current -- inactive.map(_._1) ++ active.map(_._1)
  })

  private def onConvMemberChanged(conv: ConvId) = onAdded.map(_.map(_.userId -> true)).union(onDeleted.map(_.map(_._1 -> false)))

  def getActiveUsers(conv: ConvId): Future[Seq[UserId]] = getByConv(conv) map { _.map(_.userId) }

  def getActiveConvs(user: UserId): Future[Seq[ConvId]] = getByUser(user) map { _.map(_.convId) }

  def add(conv: ConvId, users: UserId*): Future[Set[ConversationMemberData]] =
    updateOrCreateAll2(users.map((_, conv)), { (k, v) =>
      v match {
        case Some(m) => m.copy()
        case None    => ConversationMemberData(k._1, conv)
      }
    })

  def remove(conv: ConvId, users: UserId*): Future[Seq[ConversationMemberData]] = {
    getAll(users.map(_ -> conv)).flatMap(toBeRemoved => remove(users.map(_ -> conv)).map(_ => toBeRemoved.flatten))
  }

  def set(conv: ConvId, users: Seq[UserId]): Future[Unit] = getActiveUsers(conv) flatMap { active =>
    val usersSet = users.toSet
    val toRemove = active.filterNot(usersSet)
    val toAdd = usersSet -- toRemove

    remove(conv, toRemove: _*).zip(add(conv, toAdd.toSeq: _*)).map(_ => ())
  }

  def isActiveMember(conv: ConvId, user: UserId) = get(user -> conv).map(_.nonEmpty)

  def delete(conv: ConvId) = getByConv(conv) map { users => remove(users.map(_.userId -> conv)) }
}

object DefaultMembersStorage {
  private implicit val tag: LogTag = logTagFor[DefaultMembersStorage]
}
