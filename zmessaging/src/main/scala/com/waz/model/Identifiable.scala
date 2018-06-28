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
import java.util.Locale.US
import java.util.UUID
import java.util.regex.Pattern.compile

import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.NotificationsHandler.NotificationType._
import com.waz.api.ZmsVersion
import com.waz.utils.Locales.currentLocaleOrdering
import com.waz.utils.{JsonDecoder, JsonEncoder, Locales, sha2}
import com.waz.utils.wrappers.URI

import scala.math.Ordering
import scala.language.implicitConversions

trait Identifiable {
  def str: String
  override def toString = s"${getClass.getSimpleName}(${if (ZmsVersion.DEBUG) str else sha2(str)})"
}

trait Id extends Identifiable {

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

case class Name(str: String) extends Identifiable {
  def isEmpty  = str.isEmpty
  def length   = str.length
  def nonEmpty = str.nonEmpty

  def contains(substr: String): Boolean = str.contains(substr)
  def compareTo(other: Name): Int = str.compareTo(other.str)

  def split(regex: String): Array[String] = str.split(regex)
  def split(separator: Char): Array[String] = str.split(separator)

  def substring(begin: Int, end: Int): Name =
    Name(str.substring(begin, end))
}

object Name extends (String => Name) {

  implicit def fromSafeString(ss: Name): String = ss.str

  implicit def toSafeString(str: String): Name = Name(str)

  implicit val Ordering = new Ordering[Name] {
    override def compare(x: Name, y: Name) = x.compareTo(y)
  }

  val Empty = Name("")
}

case class Handle(str: String) extends Identifiable {

  def startsWithQuery(query: String): Boolean =
    str.startsWith(Handle.stripSymbol(query).toLowerCase)

  def exactMatchQuery(query: String): Boolean =
    str == Handle.stripSymbol(query).toLowerCase

  def withSymbol: String = if (str.startsWith("@")) str else s"@$str"
}

object Handle extends (String => Handle) {
  def apply(): Handle = Handle("")
  def random: Handle = Handle(UUID.randomUUID().toString)
  val handlePattern = """@(.+)""".r
  def transliterated(s: String): String = Locales.transliteration.transliterate(s).trim

  def isHandle(input: String): Boolean = input.startsWith("@")

  def stripSymbol(input: String): String = input match {
    case Handle.handlePattern(handle) => handle
    case _ => input
  }
}

case class EmailAddress(str: String) extends Identifiable {
  def normalized: Option[EmailAddress] = EmailAddress.parse(str)
}

object EmailAddress extends (String => EmailAddress) {
  implicit def IsOrdered: Ordering[EmailAddress] = currentLocaleOrdering.on(_.str)

  implicit val Encoder: JsonEncoder[EmailAddress] = JsonEncoder.build(p => js => js.put("email", p.str))
  implicit val Decoder: JsonDecoder[EmailAddress] = JsonDecoder.lift(implicit js => EmailAddress(JsonDecoder.decodeString('email)))

  val pattern = compile(address)

  def parse(input: String): Option[EmailAddress] = {
    val matcher = pattern matcher input.trim
    if (matcher.find()) Option(matcher group 2).orElse(Option(matcher group 1)) map (str => EmailAddress(str.trim.toLowerCase(US)))
    else None
  }

  /** Implements the parts of the RFC 2822 email address grammar that seem practically relevant (as of now ;) ).
    * This excludes address groups, comments, CRLF, domain literals, quoted pairs, quoted local parts and white space
    * around dot atoms. The display name part is relaxed to allow unicode characters. Also, only domain names following
    * the "preferred name syntax" (RFC 1035, 2.3.1) and consisting of at least 2 labels are considered valid.
    *
    * Here be dragons (i.e. watch out for catastrophic exponential backtracking) !
    */

  private def address = s"^(?:$mailbox)$$" // ignores "group"
  private def mailbox = s"(?:$addrSpec)|(?:$nameAddr)"
  private def nameAddr = s"(?:$displayName)?(?:$angleAddr)"
  private def angleAddr = s"$wsp*<$addrSpec>$wsp*"
  private def wsp = "[ \t]" // see 2.2.2
  private def addrSpec = s"((?:$localPart)@(?:$domain))"
  private def localPart = s"""(?:$atext)(?:\\.(?:$atext))*""" // dot-atom-text
  private def atext = "[a-zA-Z0-9!#$%&'*+\\-/=?^_`\\{|\\}~]+"
  private def displayName = s"(?:$word)+"
  private def word = s"(?:$atom)|$wsp|(?:$quotedString)"
  private def atom = "[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S}&&[^()<>\\[\\]:;@\\\\,.\"]]"
  private def quotedString = "\"[^\"]*\""
  private def domain = s"""(?:$label)(?:\\.(?:$label))+"""
  private def label = s"$letter(?:$ldhStr$letDig)?"
  private def ldhStr = "[a-zA-Z0-9-]*"
  private def letDig = "[a-zA-Z0-9]"
  private def letter = "[a-zA-Z]"
}

case class PhoneNumber(str: String) extends Identifiable

object PhoneNumber extends (String => PhoneNumber) {
  implicit def IsOrdered: Ordering[PhoneNumber] = currentLocaleOrdering.on(_.str)
  implicit val Encoder: JsonEncoder[PhoneNumber] = JsonEncoder.build(p => js => js.put("phone", p.str))
  implicit val Decoder: JsonDecoder[PhoneNumber] = JsonDecoder.lift(implicit js => PhoneNumber(JsonDecoder.decodeString('phone)))
}
