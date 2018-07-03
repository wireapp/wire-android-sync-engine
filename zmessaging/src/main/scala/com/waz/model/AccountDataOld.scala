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

import com.waz.db.Col._
import com.waz.db.{Col, Dao, DbTranslator}
import com.waz.model.AccountData.Password
import com.waz.model.AccountDataOld.TriTeamId
import com.waz.model.otr.ClientId
import com.waz.sync.client.AuthenticationManager
import com.waz.sync.client.AuthenticationManager.{AccessToken, Cookie}
import com.waz.utils.wrappers.{DBContentValues, DBCursor, DBProgram}
import com.waz.utils.{JsonDecoder, JsonEncoder}

import scala.collection.mutable

/**
  * Each AccountData row in the ZGlobal database represents one logged in user. To be logged in, they must have a cookie. Upon being forcefully
  * logged out, this entry should be removed.
  *
  * Any information that needs to be deregistered can be kept here (e.g., de-registered cookies, tokens, clients etc)
  */
case class AccountData(id:           UserId              = UserId(),
                       teamId:       Option[TeamId]      = None,
                       cookie:       Cookie              = Cookie(""), //defaults for tests
                       accessToken:  Option[AccessToken] = None,
                       pushToken:    Option[PushToken]   = None,
                       password:     Option[Password]    = None) { //password never saved to database

  override def toString: String =
    s"""AccountData:
       | id:              $id
       | teamId:          $teamId
       | cookie:          $cookie
       | accessToken:     $accessToken
       | registeredPush:  $pushToken
       | password:        $password
    """.stripMargin
}

object AccountData {

  case class Password(str: String) {
    override def toString = "********"
  }

  case class ConfirmationCode(str: String) extends AnyVal {
    override def toString: String = "******"
  }

  //Labels can be used to revoke all cookies for a given client
  //TODO save labels and use them for cleanup later
  case class Label(str: String) extends Id
  object Label extends (String => Label) with IdGen[Label]

  implicit object AccountDataDao extends Dao[AccountData, UserId] {
    val Id = id[UserId]('_id, "PRIMARY KEY").apply(_.id)

    val TeamId         = opt(id[TeamId]('team_id)).apply(_.teamId)
    val Cookie         = text[Cookie]('cookie, _.str, AuthenticationManager.Cookie)(_.cookie)
    val Token          = opt(text[AccessToken]('access_token, JsonEncoder.encodeString[AccessToken], JsonDecoder.decode[AccessToken]))(_.accessToken)
    val RegisteredPush = opt(id[PushToken]('registered_push))(_.pushToken)

    override val idCol = Id
    override val table = Table("ActiveAccounts", Id, TeamId, Cookie, Token, RegisteredPush)

    override def apply(implicit cursor: DBCursor): AccountData = AccountData(Id, TeamId, Cookie, Token, RegisteredPush)
  }

  type PermissionsMasks = (Long, Long) //self and copy permissions

  type Permission = Permission.Value
  object Permission extends Enumeration {
    val
    CreateConversation,         // 0x001
    DeleteConversation,         // 0x002
    AddTeamMember,              // 0x004
    RemoveTeamMember,           // 0x008
    AddConversationMember,      // 0x010
    RemoveConversationMember,   // 0x020
    GetBilling,                 // 0x040
    SetBilling,                 // 0x080
    SetTeamData,                // 0x100
    GetMemberPermissions,       // 0x200
    GetTeamConversations,       // 0x400
    DeleteTeam,                 // 0x800
    SetMemberPermissions        // 0x1000
    = Value
  }

  def decodeBitmask(mask: Long): Set[Permission] = {
    val builder = new mutable.SetBuilder[Permission, Set[Permission]](Set.empty)
    (0 until Permission.values.size).map(math.pow(2, _).toInt).zipWithIndex.foreach {
      case (one, pos) => if ((mask & one) != 0) builder += Permission(pos)
    }
    builder.result()
  }

  def encodeBitmask(ps: Set[Permission]): Long = {
    var mask = 0L
    (0 until Permission.values.size).map(math.pow(2, _).toLong).zipWithIndex.foreach {
      case (m, i) => if (ps.contains(Permission(i))) mask = mask | m
    }
    mask
  }
}

/**
 * This account data needs to be maintained for migration purposes - it can be deleted after a while (1 year?)
 */
case class AccountDataOld(id:              AccountId           = AccountId(),
                          teamId:          TriTeamId           = Left({}),
                          registeredPush:  Option[PushToken]   = None,
                          cookie:          Option[Cookie]      = None,
                          accessToken:     Option[AccessToken] = None,
                          userId:          Option[UserId]      = None,
                          clientId:        Option[ClientId]    = None,
                          clientRegState:  String              = "UNKNOWN",
                          privateMode:     Boolean             = false,
                          private val _selfPermissions: Long   = 0,
                          private val _copyPermissions: Long   = 0
                      ) {

  lazy val selfPermissions = AccountData.decodeBitmask(_selfPermissions)
  lazy val copyPermissions = AccountData.decodeBitmask(_copyPermissions)

}

object AccountDataOld {

  //Left is undefined, Right(None) is no team, Right(Some()) is a team
  type TriTeamId = Either[Unit, Option[TeamId]]

  implicit object AccountDataOldDao extends Dao[AccountDataOld, AccountId] {
    val Id = id[AccountId]('_id, "PRIMARY KEY").apply(_.id)

    val Team = Col[TriTeamId]("teamId", "TEXT")(new DbTranslator[TriTeamId] {
      override def save(value: TriTeamId, name: String, values: DBContentValues) = value match {
        case Left(_)        => values.putNull(name)
        case Right(None)    => values.put(name, "")
        case Right(Some(t)) => values.put(name, t.str)
      }

      override def bind(value: TriTeamId, index: Int, stmt: DBProgram) = value match {
        case Left(_)        => stmt.bindNull(index)
        case Right(None)    => stmt.bindString(index, "")
        case Right(Some(t)) => stmt.bindString(index, t.str)
      }

      override def load(cursor: DBCursor, index: Int) =
        if (cursor.isNull(index)) Left({})
        else {
          val v = cursor.getString(index)
          if (v == "") Right(None) else Right(Some(TeamId(v)))
        }
    }).apply(_.teamId)

    val RegisteredPush = opt(id[PushToken]('registered_push))(_.registeredPush)
    val Cookie = opt(text[Cookie]('cookie, _.str, AuthenticationManager.Cookie))(_.cookie)
    val Token = opt(text[AccessToken]('access_token, JsonEncoder.encodeString[AccessToken], JsonDecoder.decode[AccessToken]))(_.accessToken)
    val UserId = opt(id[UserId]('user_id)).apply(_.userId)
    val ClientId = opt(id[ClientId]('client_id))(_.clientId)
    val ClientRegState = text('reg_state)(_.clientRegState)
    val PrivateMode = bool('private_mode)(_.privateMode)
    val SelfPermissions = long('self_permissions)(_._selfPermissions)
    val CopyPermissions = long('copy_permissions)(_._copyPermissions)

    override val idCol = Id
    override val table = throw new Exception("This table is deprecated and should not be recreated")

    override def apply(implicit cursor: DBCursor): AccountDataOld = AccountDataOld(Id, Team, RegisteredPush, Cookie, Token, UserId, ClientId, ClientRegState, PrivateMode, SelfPermissions, CopyPermissions)
  }
}
