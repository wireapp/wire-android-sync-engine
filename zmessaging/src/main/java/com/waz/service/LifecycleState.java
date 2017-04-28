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
package com.waz.service;

public enum LifecycleState {
    Stopped,    // user is logged out, this zms instance should not be used at that point, it won't be able to sync anything
    Idle,       // default state, has working database and basic net client
    Active,     // Websocket channel should be active
    UiActive    // active with UI - activity is currently in foreground (after onResume, before onPause)
}
