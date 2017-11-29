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

import android.util.Base64
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.{Credentials, EmailCredentials}
import com.waz.db.Col._
import com.waz.db.{Col, Dao, DbTranslator}
import com.waz.model.AccountData.TriTeamId
import com.waz.model.otr.ClientId
import com.waz.utils.Locales.currentLocaleOrdering
import com.waz.utils.scrypt.SCrypt
import com.waz.utils.wrappers.{DB, DBContentValues, DBCursor, DBProgram}
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.AuthenticationManager
import com.waz.znet.AuthenticationManager.{Cookie, Token}
import org.json.JSONObject

import scala.collection.mutable


/**
  * Represents a user account prior to a successful registration or login with the backend. After either a successful login
  * or registration, the information here should be cleared
  *
  * @param code will not be persisted
  * @param password will not be persisted
  *
  * //TODO invitations?
  */
case class PendingAccount(teamName: Option[String]           = None,
                          email:    Option[EmailAddress]     = None,
                          handle:   Option[Handle]           = None,
                          phone:    Option[PhoneNumber]      = None,
                          name:     Option[String]           = None,
                          code:     Option[ConfirmationCode] = None,
                          password: Option[String]           = None,
                         ) {

  override def toString: String =
    s"""PendingAccount:
       | teamName:        $teamName
       | email:           $email
       | phone:           $phone
       | handle:          $handle
       | name:            $name
       | password:        In memory?: ${password.isDefined}
       | code:            $code
    """.stripMargin

  def canLogin: Boolean =
    (email.isDefined && password.isDefined) ||
    (handle.isDefined && password.isDefined) ||
    (phone.isDefined && code.isDefined)
}

object PendingAccount {

  def apply(acc: AccountData): PendingAccount = PendingAccount(
    email     = acc.email,
    handle    = acc.handle,
    phone     = acc.phone,
    name      = acc.name,
    password  = acc.password
  )

  implicit lazy val encoder: JsonEncoder[PendingAccount] = new JsonEncoder[PendingAccount] {
    override def apply(acc: PendingAccount) = JsonEncoder { o =>
      acc.teamName foreach(v => o.put("email",     v))
      acc.email    foreach(v => o.put("team_name", v.str))
      acc.handle   foreach(v => o.put("handle",    v.string))
      acc.phone    foreach(v => o.put("phone",     v.str))
      acc.name     foreach(v => o.put("name",      v))
    }
  }

  implicit lazy val decoder: JsonDecoder[PendingAccount] = new JsonDecoder[PendingAccount] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject) =
      PendingAccount('team_name, 'email, 'handle, 'phone)
  }
}

/**
  * Should only contain data that might need to exist beyond the life/scope of the user database (for cleanup or for
  * managing all accounts on the device, for example)
  */
case class AccountDataNew(userId:      UserId,
                          cookie:      Cookie,
                          accessToken: Token,
                          pushToken:   Option[PushToken]) {

  override def toString: String =
    s"""AccountData:
       | userId:          $userId
       | cookie:          $cookie
       | accessToken:     $accessToken
       | pushToken:       $pushToken
    """.stripMargin
}

object AccountDataNew {
  implicit object AccountDataNewDao extends Dao[AccountDataNew, UserId] {
    val UserId      = id[UserId]        ('user_id, "PRIMARY KEY").apply(_.userId)
    val Cookie      = text[Cookie]      ('cookie, _.str, AuthenticationManager.Cookie)(_.cookie)
    val AccessToken = text[Token]       ('access_token, JsonEncoder.encodeString[Token], JsonDecoder.decode[Token])(_.accessToken)
    val PushToken   = opt(id[PushToken] ('registered_push))(_.pushToken)

    override val idCol = UserId
    override val table = Table("Accounts_new", UserId, Cookie, AccessToken, PushToken)

    override def apply(implicit cursor: DBCursor): AccountDataNew = AccountDataNew(UserId, Cookie, AccessToken, PushToken)
  }
}

/**
 * Represents a local user account.
 *
 * @param password - will not be stored in db
 */
case class AccountData(id:              AccountId                       = AccountId(),
                       teamId:          TriTeamId                       = Left({}),
                       pendingTeamName: Option[String]                  = None,
                       email:           Option[EmailAddress]            = None,
                       phone:           Option[PhoneNumber]             = None,
                       handle:          Option[Handle]                  = None,
                       registeredPush:  Option[PushToken]               = None,
                       pendingEmail:    Option[EmailAddress]            = None,
                       pendingPhone:    Option[PhoneNumber]             = None,
                       cookie:          Option[Cookie]                  = None,
                       password:        Option[String]                  = None,
                       accessToken:     Option[Token]                   = None,
                       userId:          Option[UserId]                  = None,
                       clientId:        Option[ClientId]                = None, //DEPRECATED! use the client id (state) stored in userpreferences instead
                       clientRegState:  String                          = "UNKNOWN", //DEPRECATED! use the client id (state) stored in userpreferences instead
                       privateMode:     Boolean                         = false,
                       regWaiting:      Boolean                         = false,
                       code:            Option[ConfirmationCode]        = None,
                       name:            Option[String]                  = None,
                       invitationToken: Option[PersonalInvitationToken] = None,
                       firstLogin:      Boolean                         = true,
                       private val _selfPermissions: Long      = 0,
                       private val _copyPermissions: Long      = 0
                      ) {

  override def toString: String =
    s"""AccountData:
       | id:              $id
       | teamId:          $teamId
       | pendingTeamName: $pendingTeamName
       | email:           $email
       | phone:           $phone
       | handle:          $handle
       | registeredPush:  $registeredPush
       | pendingEmail:    $pendingEmail
       | pendingPhone:    $pendingPhone
       | cookie:          ${cookie.take(6)}
       | password:        In memory?: ${password.isDefined}
       | accessToken:     ${accessToken.take(6)}
       | userId:          $userId
       | privateMode:     $privateMode
       | regWaiting:      $regWaiting
       | code:            $code
       | name:            $name
       | invitationToken  $invitationToken
       | firstLogin       $firstLogin
       | _selfPermissions ${_selfPermissions}
       | _copyPermissions ${_copyPermissions}
    """.stripMargin


  lazy val selfPermissions = AccountData.decodeBitmask(_selfPermissions)
  lazy val copyPermissions = AccountData.decodeBitmask(_copyPermissions)

  /**
    * A pending phone that matches the current phone signifies the user is trying to login. In this case, they're account
    * needs to be re-verified
    */
  def verified =
    (phone.isDefined && pendingPhone != phone) || email.isDefined

  def authorized(credentials: Credentials) = credentials match {
    case EmailCredentials(e, Some(passwd), _) if pendingEmail.contains(e) || email.contains(e) =>
      Some(copy(password = Some(passwd)))
    case _ =>
      None
  }

  def updatedNonPending = (pendingEmail, pendingPhone) match {
    case (Some(e), _) => copy(email = Some(e), pendingEmail = None)
    case (_, Some(p)) => copy(phone = Some(p), pendingPhone = None)
    case _ => this
  }

  def updatedPending = (email, phone) match {
    case (Some(e), _) => copy(pendingEmail = Some(e), email = None)
    case (None, Some(p)) => copy(pendingPhone = Some(p), phone = None)
    case _ => this
  }

  def canLogin: Boolean = {
    (email.orElse(pendingEmail).isDefined && password.isDefined) ||
      (handle.isDefined && password.isDefined) ||
      (phone.orElse(pendingPhone).isDefined && code.isDefined)
  }

  def addToRegistrationJson(o: JSONObject) =
    addCredentialsToJson(o, isLogin = !regWaiting)

  private def addCredentialsToJson(o: JSONObject, isLogin: Boolean = true) = {
    o.put("label", id.str)  // this label can be later used for cookie revocation
    invitationToken foreach (i => o.put("invitation_code", i.code))

    (email.orElse(pendingEmail), handle, phone.orElse(pendingPhone)) match {
      case (Some(e), _, _) =>
        o.put("email", e.str)
        password foreach (o.put("password", _))

      case (_, Some(h), _) if password.isDefined =>
        o.put("handle", h.string)
        password foreach (o.put("password", _))

      case (_, _, Some(p)) =>
        o.put("phone", p.str)
       code foreach { code => o.put(if (isLogin) "code" else "phone_code" , code.str) }

      case _ =>
    }
  }

  def autoLoginOnRegistration = phone.isDefined || invitationToken.isDefined

  def updated(user: UserInfo): AccountData =
    copy(userId = Some(user.id), email = user.email.orElse(email), pendingEmail = email.fold(pendingEmail)(_ => Option.empty[EmailAddress]), phone = user.phone.orElse(phone), handle = user.handle.orElse(handle), privateMode = user.privateMode.getOrElse(privateMode))

  def isTeamAccount: Boolean =
    teamId.fold(_ => false, _.isDefined)

}

case class PhoneNumber(str: String) extends AnyVal {
  override def toString: String = str
}
object PhoneNumber extends (String => PhoneNumber) {
  implicit def IsOrdered: Ordering[PhoneNumber] = currentLocaleOrdering.on(_.str)
  implicit val Encoder: JsonEncoder[PhoneNumber] = JsonEncoder.build(p => js => js.put("phone", p.str))
  implicit val Decoder: JsonDecoder[PhoneNumber] = JsonDecoder.lift(implicit js => PhoneNumber(JsonDecoder.decodeString('phone)))
}

case class ConfirmationCode(str: String) extends AnyVal {
  override def toString: String = str
}

object AccountData {

  //TODO Might be nice to have a TriOption type...
  //Left is undefined, Right(None) is no team, Right(Some()) is a team
  type TriTeamId = Either[Unit, Option[TeamId]]

  type PermissionsMasks = (Long, Long) //self and copy permissions

  def apply(credentials: Credentials): AccountData = {
    val id = AccountId()
    val hash = credentials.maybePassword.map(computeHash(id, _)).getOrElse("")
    new AccountData(id, Left({}), password = credentials.maybePassword, handle = credentials.maybeUsername, pendingPhone = credentials.maybePhone, pendingEmail = credentials.maybeEmail)
  }

  def apply(p: PendingAccount): AccountData =
    AccountData(
      email     = p.email,
      handle    = p.handle,
      phone     = p.phone,
      name      = p.name,
      password  = p.password
    )

  def apply(email: EmailAddress, password: String): AccountData = {
    val id = AccountId()
    AccountData(id, Left({}), email = Some(email), password = Some(password), phone = None, handle = None)
  }

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

  def computeHash(id: AccountId, password: String) =
    logTime(s"compute scrypt password hash") {
      if (password.isEmpty) "" else {
        val salt = id.str.replace("-", "").getBytes("utf8").take(16)
        val hash = SCrypt.scrypt(password.getBytes("utf8"), salt, 1024, 8, 1, 32)
        Base64.encodeToString(hash, Base64.NO_WRAP)
      }
    }

  implicit object AccountDataDao extends Dao[AccountData, AccountId] {
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

    val PendingTeamName = opt(text('pending_team_name))(_.pendingTeamName)
    val Email = opt(emailAddress('email))(_.email)
    val Phone = opt(phoneNumber('phone))(_.phone)
    val Handle = opt(handle('handle))(_.handle)
    val RegisteredPush = opt(id[PushToken]('registered_push))(_.registeredPush)
    val PendingEmail = opt(emailAddress('pending_email))(_.pendingEmail)
    val PendingPhone = opt(phoneNumber('pending_phone))(_.pendingPhone)
    val Cookie = opt(text[Cookie]('cookie, _.str, AuthenticationManager.Cookie))(_.cookie)
    val Token = opt(text[Token]('access_token, JsonEncoder.encodeString[Token], JsonDecoder.decode[Token]))(_.accessToken)
    val UserId = opt(id[UserId]('user_id)).apply(_.userId)
    val ClientId = opt(id[ClientId]('client_id))(_.clientId)
    val ClientRegState = text('reg_state)(_.clientRegState)
    val PrivateMode = bool('private_mode)(_.privateMode)
    val RegWaiting = bool('reg_waiting)(_.regWaiting)
    val Code = opt(text[ConfirmationCode]('code, _.str, ConfirmationCode))(_.code)
    val InvitationToken = opt(text[PersonalInvitationToken]('invitation_token, _.code, PersonalInvitationToken))(_.invitationToken)
    val Name = opt(text('name))(_.name)
    val FirstLogin = bool('first_login)(_.firstLogin)
    val SelfPermissions = long('self_permissions)(_._selfPermissions)
    val CopyPermissions = long('copy_permissions)(_._copyPermissions)

    override val idCol = Id
    override val table = Table("Accounts", Id, Team, Email, PendingEmail, PendingPhone, PendingTeamName, Cookie, Phone, Token, UserId, ClientId, ClientRegState, Handle, PrivateMode, RegWaiting, RegisteredPush, Code, Name, InvitationToken, FirstLogin, SelfPermissions, CopyPermissions)

    override def apply(implicit cursor: DBCursor): AccountData = AccountData(Id, Team, PendingTeamName, Email, Phone, Handle, RegisteredPush, PendingEmail, PendingPhone, Cookie, None, Token, UserId, ClientId, ClientRegState, PrivateMode, RegWaiting, Code, Name, InvitationToken, FirstLogin, SelfPermissions, CopyPermissions)

    def findByEmail(email: EmailAddress)(implicit db: DB) =
      iterating(db.query(table.name, null, s"${Email.name} = ? OR ${PendingEmail.name} = ?", Array(email.str, email.str), null, null, null))

    def findByPhone(phone: PhoneNumber)(implicit db: DB) =
      iterating(db.query(table.name, null, s"${Phone.name} = ? OR ${PendingPhone.name} = ?", Array(phone.str, phone.str), null, null, null))

    def deleteForEmail(email: EmailAddress)(implicit db: DB) = delete(Email, Some(email))

    def findLoggedIn()(implicit db: DB) =
      iterating(db.query(table.name, null, s"${Cookie.name} IS NOT NULL", null, null, null, null))

    def findByPendingTeamName(name: String)(implicit db: DB) =
      iterating(db.query(table.name, null, s"${PendingTeamName.name} = ?", Array(name), null, null, null))
  }
}
