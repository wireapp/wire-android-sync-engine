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
package com.waz.service.conversation

import com.waz.ZLog._
import com.waz.content._
import com.waz.model.ConversationData.ConversationType
import com.waz.model._
import com.waz.service.SearchKey
import com.waz.service.tracking.TrackingService
import com.waz.specs.AndroidFreeSpec
import com.waz.testutils.TestUserPreferences
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{EventStream, Signal}

import scala.collection.immutable.SortedSet
import scala.concurrent.Future

class ConversationsContentUpdaterSpec extends AndroidFreeSpec {
  implicit val executionContext = new SerialDispatchQueue(name = "ConversationsContentUpdaterSpec")

  val selfUserId = UserId()

  feature("Duplicated conversations") {

    scenario("Fix duplicated conversation data") {
      val convStorage: ConversationStorage = mock[ConversationStorage]
      val teamId = Option.empty[TeamId]
      val prefs = new TestUserPreferences()
      val membersStorage: MembersStorage = mock[MembersStorage]
      val messagesStorage: MessagesStorage = mock[MessagesStorage]
      val trackingService: TrackingService = mock[TrackingService]

      val convId1 = ConvId()
      val convId2 = ConvId()
      val rConvId = RConvId()
      val otherUserId = UserId(convId1.str)
      val otherUser = UserData(otherUserId, None, "other", searchKey = SearchKey.simple("other"))
      val conv1 = ConversationData(convId1, rConvId, Some("name"), otherUserId, ConversationType.OneToOne, lastEventTime = RemoteInstant.Epoch)
      val conv2 = conv1.copy(id = convId2)

      val convs = SortedSet[ConversationData](conv1, conv2)
      val convSet = ConversationsSet(convs)

      val convUpdated = EventStream[(ConversationData, ConversationData)]()

      (trackingService.exception(_: Throwable, _: String, _: Option[UserId])(_: LogTag)).expects(*, *, *, *).anyNumberOfTimes().returning(Future.successful({}))
      (convStorage.convsSignal _).expects().anyNumberOfTimes.returning(Signal.const(convSet))
      (convStorage.convUpdated _).expects().anyNumberOfTimes.returning(convUpdated)
      (convStorage.list _).expects().once().returning(Future.successful(Seq(conv1, conv2)))

      val usersStorage: UsersStorage = mock[UsersStorage]
      (usersStorage.listAll _).expects(*).returns(Future.successful(Vector(otherUser)))

      prefs.setValue(UserPreferences.FixDuplicatedConversations, true)

      val updater = new ConversationsContentUpdaterImpl(convStorage, selfUserId, teamId, usersStorage, prefs, membersStorage, messagesStorage, trackingService)

      awaitAllTasks
      // work in progress
    }

  }

}
