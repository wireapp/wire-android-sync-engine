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
package com.waz.sync.handler

import com.waz.api.impl.ErrorResponse
import com.waz.model.{TeamData, TeamId, TeamMemberData, UserId}
import com.waz.service.teams.TeamsService
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncResult
import com.waz.sync.client.TeamsClient
import com.waz.sync.client.TeamsClient.TeamsResponse
import com.waz.threading.CancellableFuture

import scala.concurrent.Future


class TeamsSyncHandlerSpec extends AndroidFreeSpec {


  val client = mock[TeamsClient]
  val service = mock[TeamsService]

  feature("Sync teams") {

    scenario("Basic single team with some members sync") {

      val teamId = TeamId()
      val teams = Set(TeamData(teamId, "My Team"))
      val members = Set(
        TeamMemberData(UserId(), teamId, Set.empty),
        TeamMemberData(UserId(), teamId, Set.empty)
      )

      (client.getTeams _).expects(None).once().returning(CancellableFuture.successful(Right(TeamsResponse(teams, hasMore = false))))
      (client.getTeamMembers _).expects(teamId).once().returning(CancellableFuture.successful(Right(members)))

      (service.onTeamsSynced _).expects(teams, members).once().returning(Future.successful({}))

      result(initHandler.syncTeams()) shouldEqual SyncResult.Success

    }

    scenario("Paginated teams with some members sync") {

      val teamIds = Seq(TeamId("1"), TeamId("2"))
      val teams = teamIds.map { id =>
        TeamData(id, s"Team: ${id.str}")
      }
      val members = teamIds.map { id =>
        Set(
          TeamMemberData(UserId(s"User 1 team ${id.str}"), id, Set.empty),
          TeamMemberData(UserId(s"User 2 team ${id.str}"), id, Set.empty)
        )
      }

      var callsToTeams = 0
      (client.getTeams _).expects(*).twice().onCall { start: Option[TeamId] =>
        callsToTeams += 1

        val resp = callsToTeams match {
          case 1 =>
            start shouldEqual None
            TeamsResponse(teams.init.toSet, hasMore = true)
          case 2 =>
            start shouldEqual teamIds.headOption
            TeamsResponse(teams.tail.toSet, hasMore = false)
          case _ => fail("Unexpected number of calls to getTeams")
        }

        CancellableFuture.successful(Right(resp))
      }

      var callsToMembers = 0
      (client.getTeamMembers _).expects(*).twice().onCall { teamId: TeamId =>
        callsToMembers += 1

        val resp = callsToMembers match {
          case 1 =>
            teamId shouldEqual teamIds.head
            members.head
          case 2 =>
            teamId shouldEqual teamIds.last
            members.last
          case _ => fail("Unexpected number of calls to getTeamMembers")
        }

        CancellableFuture.successful(Right(resp))
      }

      var callsToSynced = 0
      (service.onTeamsSynced _).expects(*, *).twice().onCall { (ts, ms) =>
        callsToSynced += 1

        callsToSynced match {
          case 1 =>
            ts shouldEqual teams.init.toSet
            ms shouldEqual members.head
          case 2 =>
            ts shouldEqual teams.tail.toSet
            ms shouldEqual members.last
          case _ => fail("Unexpected number of calls to getTeamMembers")
        }

        Future.successful({})
      }
      result(initHandler.syncTeams()) shouldEqual SyncResult.Success
    }

    scenario("Failed members download should fail entire sync") {

      val teamId = TeamId()
      val teams = Set(TeamData(teamId, "My Team"))

      val timeoutError = ErrorResponse(ErrorResponse.ConnectionErrorCode, s"Request failed with timeout", "connection-error")

      (client.getTeams _).expects(None).once().returning(CancellableFuture.successful(Right(TeamsResponse(teams, hasMore = false))))
      (client.getTeamMembers _).expects(teamId).once().returning(CancellableFuture.successful(Left(timeoutError)))

      (service.onTeamsSynced _).expects(*, *).never().returning(Future.successful({}))

      result(initHandler.syncTeams()) shouldEqual SyncResult(timeoutError)
    }
  }

  def initHandler = new TeamsSyncHandlerImpl(client, service)

}