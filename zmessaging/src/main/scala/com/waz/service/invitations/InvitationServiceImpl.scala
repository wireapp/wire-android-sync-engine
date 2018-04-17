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
package com.waz.service.invitations

import java.util.Locale

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.content.Database
import com.waz.model.Contact.{EmailAddressesDao, PhoneNumbersDao}
import com.waz.model._
import com.waz.service._
import com.waz.service.conversation.ConversationsService
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.InvitationClient
import com.waz.sync.client.InvitationClient.ConfirmedTeamInvitation
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils._
import com.waz.utils.events.{EventContext, Signal}
import com.waz.znet.ZNetClient.ErrorOrResponse
import org.threeten.bp.Instant

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

trait InvitationService {
  def invitedContacts: Signal[Set[ContactId]]
}

class InvitationServiceImpl(selfUserId:    UserId,
                            storage:       Database,
                            users:         UserService,
                            connections:   ConnectionService,
                            contacts:      ContactsService,
                            conversations: ConversationsService,
                            sync:          SyncServiceHandle,
                            timeouts:      Timeouts,
                            client:        InvitationClient,
                            teamId:        Option[TeamId]) extends InvitationService {

  import EventContext.Implicits.global
  import timeouts.contacts._

  private implicit lazy val dispatcher = new SerialDispatchQueue(name = "serial_InvitationService")
  @volatile private var nextExpirationUpdate = Option.empty[CancellableFuture[Unit]]

  private lazy val invitedContactsSource = Signal[Set[ContactId]]()
  def invitedContacts: Signal[Set[ContactId]] = invitedContactsSource

  storage.read { implicit db =>
    returning(InvitedContacts.load)(invited => verbose(s"loaded ${invited.size} previous invitation(s)"))
  } foreach (invitedContactsSource ! _)

  contacts.contactsOnWire.throttle(userMatchingInterval) { onWire =>
    storage(InvitedContacts.forget(onWire.foresets.keys)(_)).map(_ => invitedContactsSource.mutate(_ -- onWire.foresets.keys))
  }

  def generateInvitationUri() = WebLink(WebLink.Token(selfUserId))

  def connectToInvitingUser(invite: WebLink.Token, message: String) = Future {
    invite.userId
  } flatMap {
    case Some(userId) => connections.connectToUser(userId, message, "")
    case _ =>
      verbose(s"token expired: $invite")
      CancellableFuture.successful(None)
  }

  def invite(id: ContactId, method: Either[EmailAddress, PhoneNumber], nameOfInvitee: String, message: String, locale: Option[Locale]): Future[Unit] =
    users.getSelfUser flatMap {
      case Some(self) =>
        for {
          cs <- similarTo(method)
          _  <- storage(InvitedContacts.invited(cs, Instant.now)(_)).future
          _  <- sync.postInvitation(Invitation(id, method, nameOfInvitee, self.name, message, locale))
        } yield {
          verbose(s"${cs.size} contact(s) match(es) the invitation method used to invite $id; marked them all as invited")
          invitedContactsSource mutate (_ ++ cs)
        }
      case None =>
        error(s"invitation cancelled because there is no self user available")
        Future.successful(())
    }

  def onInvitationSuccess(inv: Invitation, genesis: Instant): Future[Unit] = {
    verbose(s"invitation of ${inv.id} succeeded at $genesis")
    for {
      cs <- similarTo(inv.method)
      _  <- storage(InvitedContacts.invited(cs, genesis)(_)).future
    } yield invitedContactsSource mutate (_ ++ cs)
  }

  def onInvitationFailure(inv: Invitation): Future[Unit] = {
    verbose(s"invitation failed: ${inv.id}")
    for {
      cs <- similarTo(inv.method)
      _  <- storage(InvitedContacts.forget(cs)(_)).future
    } yield invitedContactsSource mutate (_ -- cs)
  }

  def onInvitationOfRegisteredUser(inv: Invitation, event: UserConnectionEvent): Future[Unit] = {
    verbose(s"invited already registered user: ${inv.id} -> ${event.to}")
    for {
      cs <- similarTo(inv.method)
      _  <- storage(InvitedContacts.forget(cs)(_)).future
      _  <- connections.handleUserConnectionEvents(Seq(event))
      mc <- conversations.content.convByRemoteId(event.convId)
      _  <- mc.fold2(connections.syncConversationInitiallyAfterCreation(event.convId, event.from, event.to), c => sync.syncConversations(Set(c.id)))
      _  <- contacts.addContactsOnWire(cs.iterator.map(c => (event.to, c)).to[ArrayBuffer])
      _   = invitedContactsSource mutate (_ -- cs)
    } yield ()
  }

  private def similarTo(method: Either[EmailAddress, PhoneNumber]) = storage.read(db => method.fold(EmailAddressesDao.findBy(_)(db), PhoneNumbersDao.findBy(_)(db)))
}