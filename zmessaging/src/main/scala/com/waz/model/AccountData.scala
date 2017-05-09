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
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.ClientRegistrationState
import com.waz.api.impl.{Credentials, EmailCredentials, PhoneCredentials}
import com.waz.db.Col._
import com.waz.db.Dao
import com.waz.model.otr.ClientId
import com.waz.utils.Locales.currentLocaleOrdering
import com.waz.utils.scrypt.SCrypt
import com.waz.utils.wrappers.{DB, DBCursor}
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.AuthenticationManager
import com.waz.znet.AuthenticationManager.{Cookie, Token}

/**
 * Represents a local user account.
 *
 * @param verified - true if user account has been activated
 * @param password - will not be stored in db
 */
case class AccountData(id:             AccountId,
                       email:          Option[EmailAddress],
                       hash:           String,
                       phone:          Option[PhoneNumber],
                       handle:         Option[Handle],
                       registeredPush: Option[String]          = None,
                       verified:       Boolean                 = false,
                       cookie:         Option[Cookie]          = None,
                       password:       Option[String]          = None,
                       accessToken:    Option[Token]           = None,
                       userId:         Option[UserId]          = None,
                       clientId:       Option[ClientId]        = None,
                       clientRegState: ClientRegistrationState = ClientRegistrationState.UNKNOWN,
                       privateMode:    Boolean                 = false) {

  def authorized(credentials: Credentials) = credentials match {
    case EmailCredentials(e, Some(passwd), _) if email.contains(e) && AccountData.computeHash(id, passwd) == hash =>
      Some(copy(password = Some(passwd)))
    case _ =>
      None
  }

  def updated(credentials: Credentials) = credentials match {
    case EmailCredentials(e, Some(passwd), _) =>
      copy(email = Some(e), hash = AccountData.computeHash(id, passwd), password = Some(passwd))
    case EmailCredentials(e, None, _) =>
      copy(email = Some(e))
    case PhoneCredentials(number, _, _) =>
      copy(phone = Some(number))
    case _ => this
  }

  def credentials: Credentials = (email, phone, password) match {
    case (None, Some(p), _)   => PhoneCredentials(p, None)
    case (Some(e), _, passwd) => EmailCredentials(e, passwd)
    case _ => Credentials.Empty
  }

  def updated(user: UserInfo) =
    copy(userId = Some(user.id), email = user.email.orElse(email), phone = user.phone.orElse(phone), verified = true, handle = user.handle.orElse(handle), privateMode = user.privateMode.getOrElse(privateMode))

  def updated(userId: Option[UserId], activated: Boolean, clientId: Option[ClientId], clientRegState: ClientRegistrationState) =
    copy(userId = userId orElse this.userId, verified = this.verified | activated, clientId = clientId orElse this.clientId, clientRegState = clientRegState)

  override def toString: String = s"AccountData($id, $handle, $email, $phone, $verified, user: $userId, client: $clientId, cookie: ${cookie.toString}, password: ${password.isDefined})"
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
  def apply(id: AccountId, email: String, hash: String): AccountData = AccountData(id, email = Some(EmailAddress(email)), hash, phone = None, handle = None)  // used only for testing

  def apply(id: AccountId, credentials: Credentials): AccountData =
    new AccountData(id, credentials.maybeEmail, "", phone = credentials.maybePhone, password = credentials.maybePassword, handle = credentials.maybeUsername)

  def apply(email: EmailAddress, password: String): AccountData = {
    val id = AccountId()
    AccountData(id, Some(email), computeHash(id, password), password = Some(password), phone = None, handle = None)
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
    val Email = opt(emailAddress('email))(_.email)
    val Hash = text('hash)(_.hash)
    val Phone = opt(phoneNumber('phone))(_.phone)
    val Handle = opt(handle('handle))(_.handle)
    val PushRegistered = opt(text('push_registered))(_.registeredPush)
    val EmailVerified = bool('verified)(_.verified)
    val Cookie = opt(text[Cookie]('cookie, _.str, AuthenticationManager.Cookie))(_.cookie)
    val Token = opt(text[Token]('access_token, JsonEncoder.encodeString[Token], JsonDecoder.decode[Token]))(_.accessToken)
    val UserId = opt(id[UserId]('user_id)).apply(_.userId)
    val ClientId = opt(id[ClientId]('client_id))(_.clientId)
    val ClientRegState = text[ClientRegistrationState]('reg_state, _.name(), ClientRegistrationState.valueOf)(_.clientRegState)
    val PrivateMode = bool('private_mode)(_.privateMode)

    override val idCol = Id
    override val table = Table("Accounts", Id, Email, Hash, EmailVerified, Cookie, Phone, Token, UserId, ClientId, ClientRegState, Handle, PrivateMode)

    override def apply(implicit cursor: DBCursor): AccountData = AccountData(Id, Email, Hash, Phone, Handle, PushRegistered, EmailVerified, Cookie, None, Token, UserId, ClientId, ClientRegState, PrivateMode)

    def findByEmail(email: EmailAddress)(implicit db: DB) =
      iterating(db.query(table.name, null, s"${Email.name} = ?", Array(email.str), null, null, null))

    def findByPhone(phone: PhoneNumber)(implicit db: DB) =
      iterating(db.query(table.name, null, s"${Phone.name} = ?", Array(phone.str), null, null, null))

    def deleteForEmail(email: EmailAddress)(implicit db: DB) = delete(Email, Some(email))
  }

}
