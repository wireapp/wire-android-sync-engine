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
package com.waz.api.impl

import android.os.Parcel
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api
import com.waz.api._
import com.waz.api.impl.otr.OtrClients
import com.waz.model.UserData._
import com.waz.model._
import com.waz.ui._
import com.waz.utils.{JsonEncoder, NameParts}

class User(val id: UserId, var data: UserData)(implicit ui: UiModule) extends com.waz.api.User with UiObservable with SignalLoading {

  def this(id: UserId)(implicit ui: UiModule) = this(id, UserData(id, ""))
  def this(data: UserData)(implicit ui: UiModule) = this(data.id, data)

  require(id == data.id)

  private var initials = computeInitials(data.name)
  private var firstContact = Option.empty[api.ContactDetails]

  // XXX: listen to storage directly if no ZMessaging is available
  // this is needed for Self.getUser to work, UI accesses it before zms is fully logged in
  accountLoader { acc =>
    acc.zmessaging flatMap {
      case None => acc.storage.flatMap(_.usersStorage.signal(id))
      case Some(zms) => zms.users.userSignal(id)
    }
  } { set }

  addLoader(_.contacts.contactForUser(id)) { cont =>
    firstContact = cont.map(c => ui.contactDetails.getOrElseUpdate(c.id, new ContactDetails(c, false)(ui)))
    notifyChanged()
  }

  def set(d: UserData): Unit = {
    require(this.id == d.id)
    verbose(s"set($d)")

    if (data != d) {
      if (data.name != d.name) initials = computeInitials(d.name)
      data = d
      notifyChanged()
    }
  }

  private[waz] def update(name: String = data.name, email: Option[EmailAddress] = data.email, accent: Int = data.accent, phone: Option[PhoneNumber] = data.phone, handle: Option[Handle] = data.handle): Unit = {
    set(data.copy(name = name, email = email, accent = accent, phone = phone, handle = handle))
  }

  private def computeInitials(name: String): String = NameParts.parseFrom(name).initials

  def getName = data.name

  def getInitials = initials

  def getId = id.str

  def getEmail = data.email.map(_.str).getOrElse("")

  def getPhone = data.phone.map(_.str).getOrElse("")

  def getPicture = ui.users.getPicture(data.picture)

  def getAccent = AccentColor(data.accent)

  def isConnected = data.isConnected

  override def isAutoConnection: Boolean = data.isAutoConnect

  def isConnectionAccepted = data.connection == ConnectionStatus.Accepted

  def isRelated = data.relation != Relation.Other

  override def isMe: Boolean = ui.users.selfUser.userId.contains(id)

  override def isDeleted: Boolean = data.deleted

  @deprecated("use getDisplayName instead", "2.41")
  override def getNameBasedOnConnectionState: String = getDisplayName

  override def getDisplayName: String = data.getDisplayName

  override def getOtrClients: CoreList[OtrClient] = OtrClients(id)(ui)

  override def getVerified: Verification = data.verified

  override def connect(message: String): IConversation =
    if (message.isEmpty) throw new IllegalArgumentException("Message should not be empty")
    else ui.users.connectToUser(this, message)

  override def getConversation: IConversation = ui.users.getConversation(this)

  override def getConnectionStatus = data.connection

  override def acceptConnection(): IConversation = ui.users.acceptConnection(this)

  override def block(): Unit = ui.users.blockUser(this)

  override def unblock(): IConversation = ui.users.unblockUser(this)

  override def ignoreConnection(): Unit = ui.users.ignoreConnection(this)

  override def cancelConnection(): Unit = ui.users.cancelConnection(this)

  override def isContact: Boolean = firstContact.nonEmpty

  override def getFirstContact: api.ContactDetails = firstContact.orNull

  override def equals(other: Any): Boolean = other match {
    case other: User => other.id == id
    case _ => false
  }

  override def hashCode: Int = id.hashCode

  override def toString: String = s"User(id = $id, data = $data, initials = $initials)"

  override def writeToParcel(dest: Parcel, flags: Int): Unit = dest.writeString(JsonEncoder.encodeString(data))
  override def describeContents(): Int = 0

  override def getUsername: String = data.handle.fold("")(_.string)
}

object EmptyUser extends com.waz.api.User {
  override def getName: String = ""
  override def getConnectionStatus = api.User.ConnectionStatus.UNCONNECTED
  override def getEmail: String = ""
  override def getDisplayName: String = ""
  override def block(): Unit = ()
  override def getPhone: String = ""
  override def getId: String = ""
  override def getPicture: api.ImageAsset = ImageAsset.Empty
  override def isMe: Boolean = false
  override def getConversation: IConversation = null
  override def isRelated: Boolean = false
  override def acceptConnection(): IConversation = null
  override def getNameBasedOnConnectionState: String = ""
  override def isConnected: Boolean = false
  override def unblock(): IConversation = null
  override def connect(message: String): IConversation = null
  override def getInitials: String = ""
  override def ignoreConnection(): Unit = ()
  override def getAccent: api.AccentColor = AccentColor()
  override def removeUpdateListener(listener: UpdateListener): Unit = ()
  override def addUpdateListener(listener: UpdateListener): Unit = ()
  override def writeToParcel(dest: Parcel, flags: Int): Unit = ()
  override def describeContents(): Int = 0
  override def cancelConnection(): Unit = ()
  override def getOtrClients: CoreList[OtrClient] = new EmptyList()
  override def getVerified: Verification = Verification.UNKNOWN
  override def isDeleted: Boolean = false
  override def isAutoConnection: Boolean = false
  override def isContact: Boolean = false
  override def getFirstContact: api.ContactDetails = null
  override def getUsername: String = ""
}
