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
package com.waz.service.media

import com.waz.api.MessageContent.Location
import com.waz.model.Dim2
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.client.GoogleMapsClient.StaticMapsPathBase
import com.waz.utils.wrappers.URI

class GoogleMapsMediaServiceSpec extends AndroidFreeSpec {

  feature("google map previews") {

    scenario("get the map preview url") {
      // Given
      val location = new Location(52.523842f, 13.402276f, "Head Quarters", 2)

      // When
      val actual = GoogleMapsMediaService.getImagePath(location, Dim2(100, 200))

      // Then
      val expected = URI.parse(s"$StaticMapsPathBase?center=${location.getLatitude}%2C${location.getLongitude}&zoom=2&size=100x200")
      actual shouldEqual expected
    }
  }
}
