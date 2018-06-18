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
package com.waz.model

import java.nio.ByteBuffer
import java.util.UUID

import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.NotificationsHandler.NotificationType._
import com.waz.log.InternalLog.ProductionLoggable
import com.waz.utils.wrappers.URI

trait Id extends ProductionLoggable {

  def bytes = {
    val uuid = UUID.fromString(str)
    val bytes = Array.ofDim[Byte](16)
    val bb = ByteBuffer.wrap(bytes).asLongBuffer()
    bb.put(uuid.getMostSignificantBits)
    bb.put(uuid.getLeastSignificantBits)
    bytes
  }

  def uid = Uid(str)

}

trait IdCodec[A <: Id] {
  def decode(str: String): A
  def encode(id: A): String = id.str
  def empty(): A = decode("")
}

trait IdGen[A <: Id] {
  def apply(str: String) : A

  def apply(): A = apply(UUID.randomUUID().toString)

  def apply(mostSigBits: Long, leastSigBits: Long): A = apply(new UUID(mostSigBits, leastSigBits).toString)

  val Zero = apply(new UUID(0, 0).toString)
  val Empty: A = apply("")

  implicit object Codec extends IdCodec[A] {
    def decode(str: String): A = apply(str)
  }

  implicit object IdOrdering extends Ordering[A] {
    override def compare(x: A, y: A): Int = Ordering.String.compare(x.str, y.str)
  }
}

case class Uid(str: String) extends Id
object Uid extends (String => Uid) with IdGen[Uid]

case class UserId(str: String) extends Id
object UserId extends (String => UserId) with IdGen[UserId]

case class TeamId(str: String) extends Id
object TeamId extends (String => TeamId) with IdGen[TeamId]

case class AccountId(str: String) extends Id
object AccountId extends (String => AccountId) with IdGen[AccountId]

case class AssetId(str: String) extends Id
object AssetId extends (String => AssetId) with IdGen[AssetId]

case class CacheKey(str: String) extends Id
object CacheKey extends (String => CacheKey) with IdGen[CacheKey] {
  //any appended strings should be url friendly
  def decrypted(key: CacheKey) = CacheKey(s"${key.str}_decr_")
  def fromAssetId(id: AssetId) = CacheKey(s"${id.str}")
  def fromUri(uri: URI) = CacheKey(uri.toString)
}

case class RAssetId(str: String) extends Id
object RAssetId extends (String => RAssetId) with IdGen[RAssetId]

case class MessageId(str: String) extends Id
object MessageId extends (String => MessageId) with IdGen[MessageId]

case class ConvId(str: String) extends Id
object ConvId extends (String => ConvId) with IdGen[ConvId]

case class RConvId(str: String) extends Id
object RConvId extends (String => RConvId) with IdGen[RConvId]

case class SyncId(str: String) extends Id
object SyncId extends (String => SyncId) with IdGen[SyncId]

case class PushToken(str: String) extends Id
object PushToken extends (String => PushToken) with IdGen[PushToken]

case class TrackingId(str: String) extends Id
object TrackingId extends (String => TrackingId) with IdGen[TrackingId]

case class ContactId(str: String) extends Id
object ContactId extends (String => ContactId) with IdGen[ContactId]

case class InvitationId(str: String) extends Id
object InvitationId extends (String => InvitationId) with IdGen[InvitationId]

//NotificationId
case class NotId(str: String) extends Id
object NotId extends (String => NotId) with IdGen[NotId] {
  def apply(tpe: NotificationType, userId: UserId): NotId = NotId(s"$tpe-${userId.str}")
  def apply(id: (MessageId, UserId)): NotId = NotId(s"$LIKE-${id._1.str}-${id._2.str}")
  def apply(msgId: MessageId): NotId = NotId(msgId.str)
}

case class ProviderId(str: String) extends Id
object ProviderId extends (String => ProviderId) with IdGen[ProviderId]

case class IntegrationId(str: String) extends Id
object IntegrationId extends (String => IntegrationId) with IdGen[IntegrationId]
