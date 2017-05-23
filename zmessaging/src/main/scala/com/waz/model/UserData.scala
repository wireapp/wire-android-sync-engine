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

import java.util.Date
import java.util.regex.Pattern.{CASE_INSENSITIVE, compile}

import com.waz.api.Verification
import com.waz.api.impl.AccentColor
import com.waz.db.Col._
import com.waz.db.Dao
import com.waz.model.UserData.ConnectionStatus
import com.waz.service.SearchKey
import com.waz.sync.client.UserSearchClient.UserSearchEntry
import com.waz.utils._
import com.waz.utils.wrappers.{DB, DBCursor}
import org.json.JSONObject

case class UserData(
                     id: UserId,
                     name: String,
                     email: Option[EmailAddress],
                     phone: Option[PhoneNumber],
                     trackingId: Option[TrackingId] = None,
                     picture: Option[AssetId] = None,
                     accent: Int = 0, // accent color id
                     searchKey: SearchKey,
                     connection: ConnectionStatus = ConnectionStatus.Unconnected,
                     connectionLastUpdated: Date = new Date(0), // server side timestamp of last connection update
                     connectionMessage: Option[String] = None, // incoming connection request message
                     conversation: Option[RConvId] = None, // remote conversation id with this contact (one-to-one)
                     relation: Relation = Relation.Other,
                     syncTimestamp: Long = 0,
                     displayName: String = "",
                     verified: Verification = Verification.UNKNOWN, // user is verified if he has any otr client, and all his clients are verified
                     deleted: Boolean = false,
                     handle: Option[Handle]
                   ) {

  def isConnected = ConnectionStatus.isConnected(connection)
  def hasEmailOrPhone = email.isDefined || phone.isDefined
  def isSelf = connection == ConnectionStatus.Self
  def isAcceptedOrPending = connection == ConnectionStatus.Accepted || connection == ConnectionStatus.PendingFromOther || connection == ConnectionStatus.PendingFromUser
  def isVerified = verified == Verification.VERIFIED
  def isAutoConnect = isConnected && ! isSelf && connectionMessage.isEmpty
  lazy val isWireBot = handle.exists(h => UserData.botHandle.matcher(h.string).matches)

  def getDisplayName = if (displayName.isEmpty) name else displayName

  def updated(user: UserInfo): UserData = copy(
    name = user.name.getOrElse(name),
    email = user.email.orElse(email),
    phone = user.phone.orElse(phone),
    accent = user.accentId.getOrElse(accent),
    trackingId = user.trackingId.orElse(trackingId),
    searchKey = SearchKey(user.name.getOrElse(name)),
    picture = user.mediumPicture.map(_.id).orElse(picture),
    deleted = user.deleted,
    handle = user.handle match {
      case Some(h) if !h.toString.isEmpty => Some(h)
      case _ => handle
    }
  )

  def updated(user: UserSearchEntry): UserData = copy(
    name = user.name,
    email = user.email.orElse(email),
    phone = user.phone.orElse(phone),
    searchKey = SearchKey(user.name),
    relation = user.level,
    handle = user.handle match {
      case Some(h) if !h.toString.isEmpty => Some(h)
      case _ => handle
    }
  )

  def updated(name: Option[String] = None,
              email: Option[EmailAddress] = None,
              phone: Option[PhoneNumber] = None,
              accent: Option[AccentColor] = None,
              picture: Option[AssetId] = None,
              trackingId: Option[String] = None,
              handle: Option[Handle] = None): UserData = copy(
    name = name.getOrElse(this.name),
    email = email.orElse(this.email),
    phone = phone.orElse(this.phone),
    accent = accent.fold(this.accent)(_.id),
    picture = picture.orElse(this.picture),
    searchKey = SearchKey(name.getOrElse(this.name)),
    handle = handle match {
      case Some(h) if !h.toString.isEmpty => Some(h)
      case _ => this.handle
    }
  )

  def updateConnectionStatus(status: UserData.ConnectionStatus, time: Option[Date] = None, message: Option[String] = None): UserData = {
    if (time.exists(_.before(this.connectionLastUpdated))) this
    else if (this.connection == status) time.fold(this) { time => this.copy(connectionLastUpdated = time) }
    else {
      val relation = (this.relation, status) match {
        case (_, ConnectionStatus.Accepted) => Relation.First
        case (Relation.First, _) => Relation.Other
        case (rel, _) => rel
      }

      this.copy(
        connection = status,
        relation = relation,
        connectionLastUpdated = time.getOrElse(new Date(this.connectionLastUpdated.getTime + 1)),
        connectionMessage = message.orElse(this.connectionMessage))
    }
  }
}

object UserData {

  lazy val Empty = UserData(UserId("EMPTY"), "")
  val botHandle = compile("ottothebot|annathebot", CASE_INSENSITIVE)

  type ConnectionStatus = com.waz.api.User.ConnectionStatus
  object ConnectionStatus {
    import com.waz.api.User.ConnectionStatus._

    val Unconnected = UNCONNECTED
    val PendingFromUser = PENDING_FROM_USER
    val PendingFromOther = PENDING_FROM_OTHER
    val Accepted = ACCEPTED
    val Blocked = BLOCKED
    val Ignored = IGNORED
    val Self = SELF
    val Cancelled = CANCELLED

    val codeMap = Seq(Unconnected, PendingFromOther, PendingFromUser, Accepted, Blocked, Ignored, Self, Cancelled).map(v => v.code -> v).toMap

    def apply(code: String) = codeMap.getOrElse(code, Unconnected)

    def isConnected(status: ConnectionStatus) = status == Accepted || status == Blocked || status == Self
  }

  // used for testing only
  def apply(name: String): UserData = UserData(UserId(), name, None, None, searchKey = SearchKey(name), handle = None)

  def apply(id: UserId, name: String): UserData = UserData(id, name, None, None, searchKey = SearchKey(name), handle = None)

  def apply(entry: UserSearchEntry): UserData =
    UserData(entry.id, entry.name, entry.email, entry.phone, None, None, entry.colorId, SearchKey(entry.name),
      relation = entry.level,
      connection = if (entry.connected.getOrElse(false)) ConnectionStatus.Accepted else ConnectionStatus.Unconnected,
      handle = entry.handle) // TODO: improve connection, relation, search level stuff

  def apply(user: UserInfo): UserData =
    UserData(user.id, user.name.getOrElse(""), user.email, user.phone, user.trackingId, user.mediumPicture.map(_.id),
      user.accentId.getOrElse(AccentColor().id), SearchKey(user.name.getOrElse("")), deleted = user.deleted,
      handle = user.handle)

  implicit lazy val Decoder: JsonDecoder[UserData] = new JsonDecoder[UserData] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): UserData = UserData(
      id = 'id, name = 'name, email = decodeOptEmailAddress('email), phone = decodeOptPhoneNumber('phone),
      trackingId = decodeOptId[TrackingId]('trackingId), picture = decodeOptAssetId('assetId), accent = decodeInt('accent), searchKey = SearchKey('name),
      connection = ConnectionStatus('connection), connectionLastUpdated = new Date(decodeLong('connectionLastUpdated)), connectionMessage = decodeOptString('connectionMessage),
      conversation = decodeOptRConvId('rconvId), relation = Relation.withId('relation),
      syncTimestamp = decodeLong('syncTimestamp), 'displayName, Verification.valueOf('verified), deleted = 'deleted,
      handle = decodeOptHandle('handle))
  }

  implicit lazy val Encoder: JsonEncoder[UserData] = new JsonEncoder[UserData] {
    override def apply(v: UserData): JSONObject = JsonEncoder { o =>
      o.put("id", v.id.str)
      o.put("name", v.name)
      v.email foreach (o.put("email", _))
      v.phone foreach (o.put("phone", _))
      v.trackingId foreach (id => o.put("trackingId", id.str))
      v.picture foreach (id => o.put("assetId", id.str))
      o.put("accent", v.accent)
      o.put("connection", v.connection.code)
      o.put("connectionLastUpdated", v.connectionLastUpdated.getTime)
      v.connectionMessage foreach (o.put("connectionMessage", _))
      v.conversation foreach (id => o.put("rconvId", id.str))
      o.put("relation", v.relation.id)
      o.put("syncTimestamp", v.syncTimestamp)
      o.put("displayName", v.displayName)
      o.put("verified", v.verified.name)
      o.put("deleted", v.deleted)
      v.handle foreach(u => o.put("handle", u.string))
    }
  }

  implicit object UserDataDao extends Dao[UserData, UserId] {
    val Id = id[UserId]('_id, "PRIMARY KEY").apply(_.id)
    val Name = text('name)(_.name)
    val Email = opt(emailAddress('email))(_.email)
    val Phone = opt(phoneNumber('phone))(_.phone)
    val TrackingId = opt(id[TrackingId]('tracking_id))(_.trackingId)
    val Picture = opt(id[AssetId]('picture))(_.picture)
    val Accent = int('accent)(_.accent)
    val SKey = text[SearchKey]('skey, _.asciiRepresentation, SearchKey.unsafeRestore)(_.searchKey)
    val Conn = text[ConnectionStatus]('connection, _.code, ConnectionStatus(_))(_.connection)
    val ConnTime = date('conn_timestamp)(_.connectionLastUpdated)
    val ConnMessage = opt(text('conn_msg))(_.connectionMessage)
    val Conversation = opt(id[RConvId]('conversation))(_.conversation)
    val Rel = text[Relation]('relation, _.name, Relation.valueOf)(_.relation)
    val Timestamp = long('timestamp)(_.syncTimestamp)
    val DisplayName = text('display_name)(_.displayName)
    val Verified = text[Verification]('verified, _.name, Verification.valueOf)(_.verified)
    val Deleted = bool('deleted)(_.deleted)
    val Handle = opt(handle('handle))(_.handle)

    override val idCol = Id
    override val table = Table("Users", Id, Name, Email, Phone, TrackingId, Picture, Accent, SKey, Conn, ConnTime, ConnMessage, Conversation, Rel, Timestamp, DisplayName, Verified, Deleted, Handle)

    override def apply(implicit cursor: DBCursor): UserData =
      new UserData(Id, Name, Email, Phone, TrackingId, Picture, Accent, SKey, Conn, ConnTime, ConnMessage, Conversation, Rel, Timestamp, DisplayName, Verified, Deleted, Handle)

    override def onCreate(db: DB): Unit = {
      super.onCreate(db)
      db.execSQL(s"CREATE INDEX IF NOT EXISTS Conversation_id on Users (${Id.name})")
      db.execSQL(s"CREATE INDEX IF NOT EXISTS UserData_search_key on Users (${SKey.name})")
    }

    def get(id: UserId)(implicit db: DB): Option[UserData] = single(find(Id, id)(db))

    override def getCursor(id: UserId)(implicit db: DB): DBCursor = find(Id, id)(db)

    override def delete(id: UserId)(implicit db: DB): Int = db.delete(table.name, Id.name + "=?", Array(id.toString))

    def findByConnectionStatus(status: Set[ConnectionStatus])(implicit db: DB): Managed[Iterator[UserData]] = iterating(findInSet(Conn, status))

    def listContacts(implicit db: DB) = list(db.query(table.name, null, s"(${Conn.name} = ? or ${Conn.name} = ?) and ${Deleted.name} = 0", Array(ConnectionStatus.Accepted.code, ConnectionStatus.Blocked.code), null, null, null))

    def topPeople(implicit db: DB): Managed[Iterator[UserData]] =
      search(s"${Conn.name} = ? and ${Deleted.name} = 0", Array(Conn(ConnectionStatus.Accepted)))

    def recommendedPeople(prefix: String)(implicit db: DB): Managed[Iterator[UserData]] = {
      val query = SearchKey(prefix)
      search(s"""(
                |  (
                |    (
                |      ${SKey.name} LIKE ? OR ${SKey.name} LIKE ?
                |    ) AND (${Rel.name} = '${Rel(Relation.First)}' OR ${Rel.name} = '${Rel(Relation.Second)}' OR ${Rel.name} = '${Rel(Relation.Third)}')
                |  ) OR ${Email.name} = ?
                |    OR ${Handle.name} LIKE ?
                |) AND ${Deleted.name} = 0
                |  AND ${Conn.name} != '${Conn(ConnectionStatus.Accepted)}' AND ${Conn.name} != '${Conn(ConnectionStatus.Blocked)}' AND ${Conn.name} != '${Conn(ConnectionStatus.Self)}'
              """.stripMargin,
        Array(s"${query.asciiRepresentation}%", s"% ${query.asciiRepresentation}%", prefix, s"%${query.asciiRepresentation}%"))
    }

    private def search(whereClause: String, args: Array[String])(implicit db: DB): Managed[Iterator[UserData]] =
      iterating(db.query(table.name, null, whereClause, args, null, null,
        s"case when ${Conn.name} = '${Conn(ConnectionStatus.Accepted)}' then 0 when ${Rel.name} != '${Relation.Other.name}' then 1 else 2 end ASC, ${Name.name} ASC"))

    def findWireBots(implicit db: DB) = iterating(db.query(table.name, null, s"${Email.name} like 'welcome+%@wire.com' or ${Email.name} = 'welcome@wire.com' or ${Email.name} like 'anna+%@wire.com' or ${Email.name} = 'anna@wire.com'", null, null, null, null))
  }
}
