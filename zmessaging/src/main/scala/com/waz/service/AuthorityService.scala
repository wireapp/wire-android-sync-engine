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
package com.waz.service

import com.waz.content.UserPreferences
import com.waz.model.AccountDataOld.Permission
import com.waz.model.AccountDataOld.Permission._
import com.waz.service.AuthorityService.Role
import com.waz.utils.events.{Signal, SourceSignal}

trait AuthorityService {
  def selfPermissions: Signal[Set[Permission]]
  def copyPermissions: Signal[Set[Permission]]

  def setSelfPermissions(permissions: Set[Permission]): Unit
  def setCopyPermissions(permissions: Set[Permission]): Unit

  def selfRole: Signal[Role]
}

class AuthorityServiceImpl(userPreferences: UserPreferences) extends AuthorityService {
  import AuthorityService._

  private val AdminPermissions = Permission.values -- Set(GetBilling, SetBilling, DeleteTeam)
  private val PartnerPermissions = Set(CreateConversation, GetTeamConversations)

  override val selfPermissions: SourceSignal[Set[Permission]] =
    userPreferences(UserPreferences.SelfPermissions).signal

  override val copyPermissions: SourceSignal[Set[Permission]] =
    userPreferences(UserPreferences.CopyPermissions).signal

  override def setSelfPermissions(permissions: Set[Permission]): Unit =
    selfPermissions ! permissions

  override def setCopyPermissions(permissions: Set[Permission]): Unit =
    copyPermissions ! permissions

  override val selfRole: Signal[Role] = {
    def checkRole(rolePermissions: Set[Permission], actualPermissions: Set[Permission]): Boolean =
      rolePermissions.size == actualPermissions.size && rolePermissions.subsetOf(actualPermissions)

    selfPermissions.map { ps =>
      val role: Role =
        if (checkRole(AdminPermissions, ps)) Admin
        else if (checkRole(PartnerPermissions, ps)) Partner
        else Member // ???
      role
    }.orElse(Signal.const(Unknown))
  }

}

object AuthorityService {

  sealed trait Role
  case object Guest   extends Role
  case object Admin   extends Role
  case object Member  extends Role
  case object Partner extends Role
  case object Unknown extends Role


}
