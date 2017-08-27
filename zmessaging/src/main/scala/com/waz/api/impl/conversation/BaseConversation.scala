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
package com.waz.api.impl.conversation

import android.os.Parcel
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api
import com.waz.api.impl._
import com.waz.api.{EphemeralExpiration, IConversation, MessageContent, Verification}
import com.waz.content.Uris
import com.waz.model.ConversationData
import com.waz.service.ZMessaging
import com.waz.ui.{SignalLoading, UiModule}
import com.waz.utils.JsonEncoder

import scala.collection.JavaConverters._
import scala.util.Try

abstract class BaseConversation(implicit ui: UiModule) extends IConversation with UiObservable with SignalLoading {
  import BaseConversation._
  import IConversation.Type._

  private val conversations = ui.convs

  var name: String = ""
  var data: ConversationData = ConversationData.Empty
  var selected = false

  protected def id = data.id

  addLoader(_.convsStats.selectedConversationId) { sel =>
    if (selected != sel.contains(id)) {
      selected = !selected
      verbose(s"selected changed: now $selected")
      notifyChanged()
    }
  }

  def set(data: ConversationData): Unit = {
    verbose(s"set($data)")
    if (this.data != data) {
      this.data = data
      name = conversationName(data)

      notifyChanged()
    }
  }

  def updateType(convType: IConversation.Type): IConversation = {
    if (data.convType != convType) {
      data = data.copy(convType = convType)
      notifyChanged()
    }
    this
  }

  override def getId = id.str

  def getName = name

  def getType = data.convType

  def isMuted = data.muted

  def isArchived = data.archived

  override def isSelected = selected

  def getUsers = ui.cached(Uris.ConvMembersUri(id), new MembersList(id))

  override def isEphemeral = data.ephemeral != EphemeralExpiration.NONE

  override def getEphemeralExpiration = data.ephemeral

  override def setEphemeralExpiration(expiration: EphemeralExpiration): Unit =
    ui.zms.flatMapFuture { _.convsUi.setEphemeral(id, expiration) }

  override def isMemberOfConversation: Boolean = data.isActive

  override def hasMissedCall: Boolean = data.missedCallMessage.isDefined

  override def getUnreadCount: Int = data.unreadCount.messages

  override def getFailedCount: Int = data.failedCount

  override def isMe: Boolean = data.convType == SELF

  override def setArchived(archived: Boolean): Unit = conversations.setArchived(id, archived)

  override def setMuted(muted: Boolean): Unit = conversations.setMuted(id, muted)

  override def sendMessage(msg: MessageContent): Unit = {
    debug(s"sendMessage $msg")
    conversations.sendMessage(id, msg)
  }

  override def getBackground = ImageAsset.Empty

  def isActive = data.isActive

  override def getVerified: Verification = data.verified

  def setConversationName(name: String) = conversations.setName(id, name.trim)

  override def addMembers(users: java.lang.Iterable[_ <: api.User]): Unit = conversations.addMembers(id, users.asScala.toList)

  override def removeMember(user: api.User): Unit = conversations.removeMember(id, user)

  override def leave(): Unit = conversations.leave(id)

  override def getOtherParticipant: api.User = {
    if ((data ne ConversationData.Empty) && data.convType != ONE_TO_ONE && data.convType != WAIT_FOR_CONNECTION && data.convType != INCOMING_CONNECTION)
      error(s"unexpected call, most likely UI error", new UnsupportedOperationException(s"Can't get other participant for: ${data.convType} conversation"))

    ui.getOtherParticipantForOneToOneConv(id)
  }

  override def knock(): Unit = conversations.knock(id)

  override def getIncomingKnock: Message = data.incomingKnockMessage.map(ui.messages.cachedOrNew).orNull

  override def getInputStateIndicator: InputStateIndicator = ui.cached(Uris.InputStateIndicatorUri(id), new InputStateIndicator(id))

  override def clear(): Unit = conversations.clear(id)

  override def toString: String = s"Conversation($id, $name, $data)"

  override def writeToParcel(dest: Parcel, flags: Int): Unit = dest.writeString(JsonEncoder.encodeString(data))
  override def describeContents(): Int = 0

  override def equals(other: Any): Boolean = other match {
    case other: BaseConversation => id == other.id
    case _ => false
  }

  override def hashCode = id.hashCode
}

object BaseConversation {

  lazy val UnknownName = Try(ZMessaging.context.getResources.getString(com.waz.zms.R.string.zms_unknown_conversation_name)).getOrElse("…")

  private def conversationName(data: ConversationData) = {
    val name = if (data.convType == IConversation.Type.GROUP) data.name.filter(!_.isEmpty).getOrElse(data.generatedName) else data.generatedName
    if (name.isEmpty) {
      warn(s"Name is empty for: $data")
      UnknownName
    } else name
  }
}
